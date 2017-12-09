package com.jenkins

import groovy.json.JsonSlurper
import hudson.AbortException
import org.jenkinsci.plugins.workflow.cps.DSL
import com.cloudbees.groovy.cps.NonCPS;

class RemoteJob {
    final String STATUS_NOT_STARTED = "NOT_STARTED"
    final String STATUS_RUNNING = "RUNNING"
    final String STATUS_SUCCESS = "SUCCESS"
    final int HTTP_TIMEOUT = 30 * 1000

    int pollInterval = 10
    boolean debug

    final DSL dsl

    final String jobUrl
    final String jobToken
    final String authToken

    RemoteJob(DSL dsl, String host, String jobName, String jobToken, String username, String apiToken) {
        this.jobUrl = "${host}/job/" + encodeValue(jobName)
        this.jobToken = encodeValue(jobToken)
        this.authToken = "${username}:${apiToken}".getBytes().encodeBase64().toString()
        this.dsl = dsl
    }

    String startJob(Map conf) {
        Map localConf = [token: jobToken]
        boolean isParametrized = isJobParametrized()

        if (isParametrized && conf) {
            assert !conf.containsKey("token"), "'token' keyword is reserved"
            localConf << conf
        }

        Closure<String> handler = { HttpURLConnection conn ->
            assert conn.responseCode == 201, "${conn.responseCode} == 201"

            String location = conn.getHeaderField("Location")?.trim()
            assert location, "Location header is returned"

            return trimUrl(location)
        }

        dsl.echo("Triggering remote job: ${jobUrl}")

        String buildCmd = isParametrized ? "buildWithParameters" : "build"
        String params = buildParamsUrl(localConf)
        String queueUrl = httpRequest(
                "GET", "${jobUrl}/${buildCmd}?${params}", handler)

        dsl.echo("Job scheduled in location: ${queueUrl}")

        return queueUrl
    }

    private boolean isJobParametrized() {
        Closure<Boolean> handler = { HttpURLConnection conn ->
            assert conn.responseCode == 200, "${conn.responseCode} == 20="

            def json  = new JsonSlurper().parse(conn.inputStream)
            def actions = json.actions ?: []
            for (Map rec in actions) {
                if ("parameterDefinitions" in rec) {
                    return true
                }
            }

            return false
        }

        return httpRequest("GET", "${jobUrl}/api/json", handler)
    }

    String waitJob(String queueUrl) {
        String buildUrl = getBuildUrl(queueUrl)
        dsl.echo("The build URL is ${buildUrl}")

        String status = getBuildStatus(buildUrl)
        while(status == STATUS_NOT_STARTED) {
            waitRemoteLogic("start")
            status = getBuildStatus(buildUrl)
        }
        while(status == STATUS_RUNNING) {
            waitRemoteLogic("finish")
            status = getBuildStatus(buildUrl)
        }

        String msg = "Remote build (${buildUrl}) finished with status ${status}."
        if (status != STATUS_SUCCESS) {
            throw new AbortException(msg)
        }
        dsl.echo(msg)

        return buildUrl
    }

    String getOutput(String buildUrl) {
        Closure<String> handler = { HttpURLConnection conn ->
            // No assertions here, job is finished successfully, we cannot
            // fail the build even if Jenkins does not return the console output
            return conn.inputStream.readLines().join("\n")
        }
        return httpRequest("GET", "${buildUrl}/consoleText", handler)
    }

    private String getBuildUrl(String queueUrl) {
        Closure<String> handler = { HttpURLConnection conn ->
            assert conn.responseCode == 200, "${conn.responseCode} == 200"

            def json  = new JsonSlurper().parse(conn.inputStream)
            String url = json.executable?.url

            return url ? trimUrl(url) : STATUS_NOT_STARTED
        }

        Closure<String> cb = {
            httpRequest("GET", "${queueUrl}/api/json", handler)
        }

        String url = cb()
        while (url == STATUS_NOT_STARTED) {
            waitRemoteLogic("be picked by executor")
            url = cb()
        }

        return url
    }

    static trimUrl(String url) {
        return url.endsWith("/") ? url[0..-2] : url
    }

    static String buildParamsUrl(Map params) {
        return params.collect { it ->
            return encodeValue(it.key.toString()) + "=" + encodeValue(it.value.toString())
        }.join("&")
    }

    @NonCPS
    static String encodeValue(String orig) {
        return URLEncoder.encode(orig, "UTF-8").replace("+", "%20")
    }

    private void waitRemoteLogic(String word) {
        dsl.echo("Waiting for remote build to ${word}.")
        dsl.echo("Waiting for " + pollInterval + " seconds until next poll.")
        sleep(pollInterval * 1000, {
            throw new Exception("The build was interrupted")
        })
    }

    private String getBuildStatus(String buildUrl) {
        Closure<String> handler = { HttpURLConnection conn ->
            assert conn.responseCode == 200, "${conn.responseCode} == 200"
            def json  = new JsonSlurper().parse(conn.inputStream)

            if (json.result) {
                return json.result.toString()
            } else if (json.building) {
                return STATUS_RUNNING
            } else {
                return STATUS_NOT_STARTED
            }
        }
        return httpRequest("GET", "${buildUrl}/api/json", handler)
    }

    private <T> T httpRequest(String method, String url, Closure<T> responseHandler) {
        if (debug) {
            dsl.echo("[DEBUG] Send ${method} ${url}")
        }

        HttpURLConnection conn = (HttpURLConnection)url.toURL().openConnection()

        conn.setRequestMethod("GET")
        conn.setConnectTimeout(HTTP_TIMEOUT)
        conn.setReadTimeout(HTTP_TIMEOUT)
        conn.setRequestProperty("Authorization", "Basic ${authToken}")
        conn.setRequestProperty("Accept", "application/json")
        conn.connect()

        if (debug) {
            dsl.echo("[DEBUG] Response code=${conn.responseCode} msg=${conn.responseMessage}")
        }

        return responseHandler(conn)
    }
}