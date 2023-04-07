package org.mifos.integrationtest.cucumber.stepdef;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.specification.MultiPartSpecification;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.io.IOUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.mifos.integrationtest.common.Utils;
import org.mifos.integrationtest.config.KafkaConfig;
import org.mifos.integrationtest.config.ZeebeOperationsConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static com.google.common.truth.Truth.assertThat;

public class ZeebeStepDef extends BaseStepDef{
    @Autowired
    ZeebeOperationsConfig zeebeOperationsConfig;
    @Autowired
    KafkaConfig kafkaConfig;

    public static int startEventCount;
    public static int endEventCount;

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String BPMN_FILE_URL = "https://raw.githubusercontent.com/arkadasfynarfin/ph-ee-env-labs/zeebe-upgrade/orchestration/feel/zeebe-test.bpmn";

    Logger logger = LoggerFactory.getLogger(this.getClass());

    @When("I upload the BPMN file to zeebe")
    public void uploadBpmnFileToZeebe() throws MalformedURLException {
        String fileContent = getFileContent(BPMN_FILE_URL);
        BaseStepDef.response = uploadBPMNFile(fileContent);
        logger.info("BPMN file upload response: {}", BaseStepDef.response);
    }

    @And("I can start test workflow n times with message {string}")
    public void iCanStartTestWorkflowNTimesWithMessage(String message) {
        logger.info("Test workflow started");
        String requestBody = String.format("{ \"message\": \"%s\" }", message);
        String endpoint= zeebeOperationsConfig.workflowEndpoint +"zeebe-test";
        logger.info("Endpoint: {}", endpoint);
        logger.info("Request Body: {}", requestBody);

        for (int i=0; i<=zeebeOperationsConfig.noOfWorkflows;i++) {
            BaseStepDef.response = sendWorkflowRequest(endpoint, requestBody);
            logger.info("Workflow Response {}: {}", i, BaseStepDef.response);
        }

        logger.info("Test workflow ended");
    }

    @Then("I listen on kafka topic")
    public void listen() throws UnknownHostException {
        int counter = 0;
        logger.info("counter: {}", startEventCount);
        if(zeebeOperationsConfig.zeebeTest) {
            logger.info("kafka broker: {}", kafkaConfig.kafkaBroker);
            KafkaConsumer<String, String> consumer = createKafkaConsumer();
            while (counter < zeebeOperationsConfig.noOfWorkflows) {
                logger.info("iteration {}", counter);
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                if(!records.isEmpty()){
                    processKafkaRecords(records);
                }
                else{
                    logger.info("No records available");
                }
                counter++;
            }
            consumer.close();
        }
    }

    @And("The number of workflows started should be equal to number of message consumed on kafka topic")
    public void verifyNumberOfWorkflowsStartedEqualsNumberOfMessagesConsumed() {
        logger.info("No of workflows started: {}", zeebeOperationsConfig.noOfWorkflows);
        logger.info("No of records consumed: {}", startEventCount);
        logger.info("No of records exported: {}", endEventCount);
        assertThat(zeebeOperationsConfig.noOfWorkflows).isEqualTo(startEventCount);
        assertThat(startEventCount).isEqualTo(endEventCount);
    }

    private String getFileContent(String fileUrl) {
        try {
            return IOUtils.toString(URI.create(fileUrl), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String uploadBPMNFile(String fileContent) throws MalformedURLException {
        RequestSpecification requestSpec = Utils.getDefaultSpec();
        return RestAssured.given(requestSpec)
                .baseUri(zeebeOperationsConfig.zeebeOperationContactPoint)
                .multiPart(getMultiPart(fileContent))
                .expect()
                .spec(new ResponseSpecBuilder().expectStatusCode(200).build())
                .when()
                .post(zeebeOperationsConfig.uploadBpmnEndpoint)
                .andReturn().asString();
    }

    private MultiPartSpecification getMultiPart(String fileContent) {
        return new MultiPartSpecBuilder(fileContent.getBytes()).
                fileName("zeebe-test.bpmn").
                controlName("file").
                mimeType("text/plain").
                build();
    }

    private String sendWorkflowRequest(String endpoint, String requestBody){
        RequestSpecification requestSpec = Utils.getDefaultSpec();
        return RestAssured.given(requestSpec)
                .baseUri(zeebeOperationsConfig.zeebeOperationContactPoint)
                .body(requestBody)
                .expect()
                .spec(new ResponseSpecBuilder().expectStatusCode(200).build())
                .when()
                .post(endpoint)
                .andReturn().asString();
    }

    private KafkaConsumer<String, String> createKafkaConsumer() throws UnknownHostException {
        logger.info("inside create kafka consumer");
        Properties properties = new Properties();
        properties.put("bootstrap.servers", kafkaConfig.kafkaBroker);
        properties.put("client.id", InetAddress.getLocalHost().getHostName());
        properties.put("group.id", InetAddress.getLocalHost().getHostName());
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        logger.info("properties initialzed");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        logger.info("consumer created");
        consumer.subscribe(Collections.singletonList(kafkaConfig.kafkaTopic));
        return consumer;
    }

    private void processKafkaRecords(ConsumerRecords<String, String> records){
        for (ConsumerRecord<String, String> record : records) {
            JsonObject payload = JsonParser.parseString(record.value()).getAsJsonObject();
            JsonObject value = payload.get("value").getAsJsonObject();
            String bpmnElementType = value.get("bpmnElementType").isJsonNull() ?"": value.get("bpmnElementType").getAsString();
            String bpmnProcessId =value.get("bpmnProcessId").isJsonNull() ?"": value.get("bpmnProcessId").getAsString();
            System.out.printf("offset = %d, key = %s, value = %s%n", record.offset(), record.key(), record.value());
            logger.info("value {}", record.value());

            if(bpmnElementType.matches("START_EVENT") && bpmnProcessId.matches("zeebe-test"))
                startEventCount++;

            if(bpmnElementType.matches("END_EVENT") && bpmnProcessId.matches("zeebe-test"))
                endEventCount++;
        }
    }
}