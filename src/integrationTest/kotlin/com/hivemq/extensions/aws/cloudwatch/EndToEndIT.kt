package com.hivemq.extensions.aws.cloudwatch

import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.Network
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service.CLOUDWATCH
import org.testcontainers.hivemq.HiveMQContainer
import org.testcontainers.utility.MountableFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest
import software.amazon.awssdk.services.cloudwatch.model.Metric
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery
import software.amazon.awssdk.services.cloudwatch.model.MetricStat
import software.amazon.awssdk.services.cloudwatch.model.Statistic
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class EndToEndIT {

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    fun endToEnd() {
        val network = Network.newNetwork()

        val localStack = LocalStackContainer(LOCALSTACK_DOCKER_IMAGE).apply {
            withServices(CLOUDWATCH)
            withNetwork(network)
            withNetworkAliases("localstack")
        }
        localStack.start()

        val hivemq = HiveMQContainer(HIVEMQ_DOCKER_IMAGE).apply {
            withExtension(MountableFile.forClasspathResource("hivemq-aws-cloudwatch-extension"))
            withFileInExtensionHomeFolder(
                MountableFile.forClasspathResource("extension-config.xml"),
                "hivemq-aws-cloudwatch-extension",
                "extension-config.xml"
            )
            withEnv("AWS_ENDPOINT_OVERRIDE", URI("http://localstack:4566").toString())
            withEnv("AWS_REGION_OVERRIDE", localStack.region)
            withEnv("AWS_ACCESS_KEY_ID", localStack.accessKey)
            withEnv("AWS_SECRET_ACCESS_KEY", localStack.secretKey)
            withLogConsumer { println("HIVEMQ: " + it.utf8StringWithoutLineEnding) }
            withNetwork(network)
        }
        hivemq.start()

        val credentialsProvider =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(localStack.accessKey, localStack.secretKey))

        val cloudWatchClient = CloudWatchClient.builder() //
            .credentialsProvider(credentialsProvider) //
            .endpointOverride(localStack.getEndpointOverride(CLOUDWATCH)) //
            .region(Region.of(localStack.region)) //
            .build()


        await().timeout(Duration.ofMinutes(2)).until {
            cloudWatchClient.listMetrics().metrics().any {
                it.metricName() == "com.hivemq.messages.incoming.publish.count"
            }
        }

        val metric = Metric.builder()//
            .namespace("hivemq-metrics")//
            .metricName("com.hivemq.messages.incoming.publish.count")//
            .dimensions(emptyList())//
            .build()

        val metricStat = MetricStat.builder()
            .stat(Statistic.MAXIMUM.toString())
            .period(60)
            .metric(metric)
            .build()

        val metricDataQuery = MetricDataQuery.builder()
            .id("m1")
            .metricStat(metricStat)
            .returnData(true)
            .build()

        await().timeout(Duration.ofMinutes(2)).until {
            val request = GetMetricDataRequest.builder()
                .startTime(Instant.now().minusSeconds(3600))
                .endTime(Instant.now())
                .metricDataQueries(listOf(metricDataQuery))
                .build()
            val response = cloudWatchClient.getMetricData(request)
            response.metricDataResults().maxOf {
                it.values()[0]
            } == 0.0
        }

        val mqttClient = Mqtt5Client.builder().serverHost(hivemq.host).serverPort(hivemq.mqttPort).buildBlocking()
        mqttClient.connect()
        mqttClient.publishWith().topic("wabern").send()

        await().timeout(Duration.ofMinutes(2)).until {
            val request = GetMetricDataRequest.builder()
                .startTime(Instant.now().minusSeconds(3600))
                .endTime(Instant.now())
                .metricDataQueries(listOf(metricDataQuery))
                .build()
            val response = cloudWatchClient.getMetricData(request)
            response.metricDataResults().maxOf {
                it.values()[0]
            } == 1.0
        }

        cloudWatchClient.close()
    }
}
