package com.thomasguett.demo;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.response.EvaluateDecisionResponse;
import io.camunda.zeebe.client.api.response.ProcessInstanceResult;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.impl.oauth.OAuthCredentialsProvider;
import io.camunda.zeebe.client.impl.oauth.OAuthCredentialsProviderBuilder;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@RestController
@Configuration
@EnableAutoConfiguration
public class Application {
    @Value("${jobType:}")
    private String jobType;
    private String zeebeAddress = System.getenv("ZEEBE_ADDRESS");
    private String oauthUrl = System.getenv("CAMUNDA_OAUTH_URL");
    private String clientId = System.getenv("ZEEBE_CLIENT_ID");
    private String clientSecret = System.getenv("ZEEBE_CLIENT_SECRET");

    // Websocket handling
    @Autowired
    private SimpMessagingTemplate template;

    OAuthCredentialsProvider credentialsProvider =
            new OAuthCredentialsProviderBuilder()
                    .authorizationServerUrl(oauthUrl)
                    .audience(zeebeAddress)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .build();

    private ZeebeClient client = ZeebeClient.newClientBuilder()
            .gatewayAddress(zeebeAddress)
            .credentialsProvider(credentialsProvider)
            .build();


    JobWorker pageHandler = client.newWorker()
            .jobType("pageUpdate")
            .handler(new HandlePageChange())
            .timeout(100000L)
            .open();

    {
        System.out.println("opening job worker");
    }

    public static void main(String[] args) { SpringApplication.run(Application.class, args);}

    @GetMapping(value = "/testmessage/{requestAmount}")
    public String testMessage(@PathVariable Long requestAmount) {
        System.out.println("sending message");
        Map<String,Object> variablesMap = new HashMap<>();
        variablesMap.put("requestAmount", requestAmount);

        client.newPublishMessageCommand()
                .messageName("getNextPage")
                .correlationKey("123")
                .variables(variablesMap)
                .send();
        return "sent message";
    }

    @GetMapping(value = "/testdecision/{requestAmount}")
    public String testDecisionCall(@PathVariable Long requestAmount) {
        System.out.println("calling decision");
        Map<String, Object> variablesMap = new HashMap<>();
        variablesMap.put("requestAmount", requestAmount);

        EvaluateDecisionResponse decisionResponse = client.newEvaluateDecisionCommand()
                .decisionId("Decision_1eykg0e")
                .variables(variablesMap)
                .send()
                .join();

        // send message to websocket
        template.convertAndSend(new PageNotification(decisionResponse.getDecisionOutput()));

        return "decided page: " + decisionResponse.getDecisionOutput();
    }

    @GetMapping(value = "/test/{requestAmount}")
    public String test(@PathVariable Long requestAmount) {
        System.out.println("starting instance: ");
        Map<String,Object> variablesMap = new HashMap<>();
        variablesMap.put("requestAmount", requestAmount);

        ProcessInstanceResult result = client.newCreateInstanceCommand()
                .bpmnProcessId("steer-ui-flow")
                .latestVersion()
                .variables(variablesMap)
                .withResult()
                .requestTimeout(Duration.ofMinutes(10))
                .send()
                .join();

        Object nextpage = result.getVariablesAsMap().getOrDefault("nextPage", null);
        if(null != nextpage) {
            System.out.println("next page: " + nextpage);
        }
        return "nextpage: " + nextpage;
    }

    // JobHandler Class
    private static class HandlePageChange implements JobHandler {
        @Override
        public void handle(final JobClient client, final ActivatedJob job) {
            // here: business logic that is executed with every job
            System.out.println(job);
            client.newCompleteCommand(job.getKey()).send().join();
        }
    }
}
