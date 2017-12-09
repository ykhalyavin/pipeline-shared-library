package com.jenkins

class RemoteJobTest extends GroovyTestCase {
    RemoteJob obj
    String host = "http://localhost:8080"
    String jobName = "xtest one two"

    void setUp() {
        super.setUp()

        obj = new RemoteJob(host, jobName, "xtest_token","bot",
                "464e0d016128c4f7128fadcda97a90b4")
        obj.setDebug(true)
    }

    void testStartJob() {
        String queueUrl = obj.startJob(p1: "+test mega & value+")
        assertTrue(queueUrl.startsWith(host))

        String buildUrl = obj.waitJob(queueUrl)
        String jobNameEncoded = RemoteJob.encodeValue(jobName)
        assertTrue(buildUrl ==~ "${host}/job/${jobNameEncoded}/\\d+")
    }

    void testTrimUrl() {
        assertEquals(RemoteJob.trimUrl("http://x.com/"), "http://x.com")
        assertEquals(RemoteJob.trimUrl("http://x.com"), "http://x.com")
    }

    void testEncodeValue() {
        assertEquals(RemoteJob.encodeValue("+a b"), "%2Ba%20b")
    }
}
