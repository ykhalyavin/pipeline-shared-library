import com.jenkins.RemoteJob

def call(Map conf) {
    def required = ["credentials", "host", "jobName", "jobToken"]

    required.each { k ->
        assert conf.get(k), "'${k}' param is required"
    }
    assert conf.credentials.contains(":"), "'credentials' must be 'username:apiToken'"

    def (username, apiToken) = conf.credentials.split(":")
    RemoteJob job = new RemoteJob(
            steps,
            conf.host as String,
            conf.jobName as String,
            conf.jobToken as String,
            username as String,
            apiToken as String
    )

    String queueUrl = job.startJob(conf.params as Map)
    String buildUrl = job.waitJob(queueUrl)
    String output = job.getOutput(buildUrl)

    echo("Console output of remote job:")
    echo("-" * 80)
    echo(output)
    echo("-" * 80)
}
