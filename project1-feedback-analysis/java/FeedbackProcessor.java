package com.farina.feedback;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import software.amazon.awssdk.services.comprehend.ComprehendClient;
import software.amazon.awssdk.services.comprehend.model.DetectSentimentRequest;
import software.amazon.awssdk.services.comprehend.model.DetectSentimentResponse;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.CSVPrinter;

import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import java.util.HashMap;
import java.util.Map;

public class FeedbackProcessor {

    // Environment variables are safer than hard-coding AWS resource identifiers.
    private static final String BUCKET_NAME =
            getEnvOrDefault("FEEDBACK_BUCKET", "your-feedback-bucket");

    private static final String CLEANED_PATH =
            getEnvOrDefault("CLEANED_PATH", "cleaned-feedback/");

    private static final String PROCESSED_PATH =
            getEnvOrDefault("PROCESSED_PATH", "processed-feedback/");

    private static final String DYNAMO_TABLE =
            getEnvOrDefault("DYNAMO_TABLE", "FeedbackTable");

    private static final String SNS_TOPIC_ARN =
            getEnvOrDefault("SNS_TOPIC_ARN", "");

    public static void main(String[] args) {

        Region region = Region.US_EAST_1;

        try (
                S3Client s3 = S3Client.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();

                ComprehendClient comprehend = ComprehendClient.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();

                DynamoDbClient dynamoDb = DynamoDbClient.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();

                SnsClient snsClient = SnsClient.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build()
        ) {

            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(BUCKET_NAME)
                    .prefix(CLEANED_PATH)
                    .build();

            for (S3Object obj : s3.listObjectsV2(listReq).contents()) {

                if (!obj.key().endsWith(".csv")) {
                    continue;
                }

                System.out.println("Processing file: " + obj.key());

                processFile(
                        obj,
                        s3,
                        comprehend,
                        dynamoDb,
                        snsClient
                );
            }

            System.out.println("All feedback files processed.");

        } catch (Exception e) {
            System.err.println(
                    "Application-level processing failure: " + e.getMessage()
            );
            e.printStackTrace();
        }
    }

    private static void processFile(
            S3Object obj,
            S3Client s3,
            ComprehendClient comprehend,
            DynamoDbClient dynamoDb,
            SnsClient snsClient
    ) {

        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(obj.key())
                .build();

        try (
                InputStream s3InputStream = s3.getObject(getReq);

                CSVParser parser = CSVFormat.DEFAULT
                        .withFirstRecordAsHeader()
                        .withIgnoreHeaderCase()
                        .parse(new InputStreamReader(s3InputStream))
        ) {

            ByteArrayOutputStream outStream = new ByteArrayOutputStream();

            CSVPrinter printer = new CSVPrinter(
                    new OutputStreamWriter(outStream),
                    CSVFormat.DEFAULT.withHeader(
                            "id",
                            "feedback",
                            "sentiment",
                            "category",
                            "severity",
                            "recommended_owner",
                            "needs_escalation",
                            "processed_date"
                    )
            );

            for (CSVRecord record : parser) {

                try {
                    processFeedbackRecord(
                            record,
                            printer,
                            comprehend,
                            dynamoDb,
                            snsClient
                    );

                } catch (Exception recordException) {

                    System.err.println(
                            "Failed to process record: "
                                    + recordException.getMessage()
                    );
                }
            }

            printer.flush();

            String fileName = obj.key()
                    .substring(obj.key().lastIndexOf("/") + 1);

            String outputKey = PROCESSED_PATH + fileName;

            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(outputKey)
                            .build(),

                    RequestBody.fromBytes(outStream.toByteArray())
            );

            System.out.println(
                    "Processed file uploaded to: " + outputKey
            );

        } catch (Exception e) {

            System.err.println(
                    "Error processing file "
                            + obj.key()
                            + ": "
                            + e.getMessage()
            );
        }
    }

    private static void processFeedbackRecord(
            CSVRecord record,
            CSVPrinter printer,
            ComprehendClient comprehend,
            DynamoDbClient dynamoDb,
            SnsClient snsClient
    ) throws Exception {

        String id = record.get("id").trim();
        String feedback = record.get("feedback").trim();

        if (id.isEmpty() || feedback.isEmpty()) {

            System.out.println(
                    "Skipping empty feedback record."
            );

            return;
        }

        // 1. AI/NLP sentiment analysis
        DetectSentimentResponse detectRes =
                comprehend.detectSentiment(
                        DetectSentimentRequest.builder()
                                .text(feedback)
                                .languageCode("en")
                                .build()
                );

        String sentiment =
                detectRes.sentimentAsString();

        // 2. Business workflow enrichment
        String category =
                categorizeFeedback(feedback);

        String severity =
                determineSeverity(feedback, sentiment);

        String recommendedOwner =
                determineOwner(category);

        boolean needsEscalation =
                shouldEscalate(sentiment, severity);

        String processedDate =
                DateTimeFormatter.ISO_INSTANT
                        .format(Instant.now());

        // 3. CSV output
        printer.printRecord(
                id,
                feedback,
                sentiment,
                category,
                severity,
                recommendedOwner,
                needsEscalation,
                processedDate
        );

        // 4. DynamoDB
        Map<String, AttributeValue> item =
                new HashMap<>();

        item.put(
                "FeedbackID",
                AttributeValue.builder()
                        .s(id)
                        .build()
        );

        item.put(
                "FeedbackText",
                AttributeValue.builder()
                        .s(feedback)
                        .build()
        );

        item.put(
                "Sentiment",
                AttributeValue.builder()
                        .s(sentiment)
                        .build()
        );

        item.put(
                "Category",
                AttributeValue.builder()
                        .s(category)
                        .build()
        );

        item.put(
                "Severity",
                AttributeValue.builder()
                        .s(severity)
                        .build()
        );

        item.put(
                "RecommendedOwner",
                AttributeValue.builder()
                        .s(recommendedOwner)
                        .build()
        );

        item.put(
                "NeedsEscalation",
                AttributeValue.builder()
                        .bool(needsEscalation)
                        .build()
        );

        item.put(
                "ProcessedDate",
                AttributeValue.builder()
                        .s(processedDate)
                        .build()
        );

        item.put(
                "Status",
                AttributeValue.builder()
                        .s("SUCCESS")
                        .build()
        );

        dynamoDb.putItem(
                PutItemRequest.builder()
                        .tableName(DYNAMO_TABLE)
                        .item(item)
                        .build()
        );

        // 5. Only alert support for high-priority feedback
        if (needsEscalation && !SNS_TOPIC_ARN.isEmpty()) {

            String alertMessage =
                    "HIGH PRIORITY CUSTOMER FEEDBACK\n\n"
                            + "Feedback ID: " + id + "\n"
                            + "Sentiment: " + sentiment + "\n"
                            + "Category: " + category + "\n"
                            + "Severity: " + severity + "\n"
                            + "Recommended Owner: "
                            + recommendedOwner + "\n\n"
                            + "Customer Feedback:\n"
                            + feedback;

            snsClient.publish(
                    PublishRequest.builder()
                            .topicArn(SNS_TOPIC_ARN)
                            .subject(
                                    "Customer Feedback Escalation"
                            )
                            .message(alertMessage)
                            .build()
            );

            System.out.println(
                    "Escalation alert sent for ID: " + id
            );
        }

        System.out.println(
                "Processed ID "
                        + id
                        + " | "
                        + sentiment
                        + " | "
                        + category
                        + " | "
                        + severity
                        + " | Escalate: "
                        + needsEscalation
        );
    }

    /*
     * Categorization rules
     */

    private static String categorizeFeedback(
            String feedback
    ) {

        String text = feedback.toLowerCase();

        if (
                containsAny(
                        text,
                        "payment",
                        "charged",
                        "charge",
                        "billing",
                        "refund",
                        "invoice",
                        "card"
                )
        ) {
            return "BILLING";
        }

        if (
                containsAny(
                        text,
                        "login",
                        "password",
                        "account",
                        "locked",
                        "sign in"
                )
        ) {
            return "ACCOUNT_ACCESS";
        }

        if (
                containsAny(
                        text,
                        "crash",
                        "error",
                        "bug",
                        "broken",
                        "not working",
                        "failed",
                        "failure"
                )
        ) {
            return "TECHNICAL";
        }

        if (
                containsAny(
                        text,
                        "support",
                        "agent",
                        "representative",
                        "response",
                        "wait",
                        "waiting"
                )
        ) {
            return "CUSTOMER_SUPPORT";
        }

        if (
                containsAny(
                        text,
                        "feature",
                        "wish",
                        "suggest",
                        "improve",
                        "request"
                )
        ) {
            return "FEATURE_REQUEST";
        }

        return "GENERAL";
    }

    /*
     * Severity rules
     */

    private static String determineSeverity(
            String feedback,
            String sentiment
    ) {

        String text = feedback.toLowerCase();

        if (
                containsAny(
                        text,
                        "charged twice",
                        "charged three",
                        "multiple charges",
                        "fraud",
                        "locked out",
                        "cannot access",
                        "can't access",
                        "data loss",
                        "security",
                        "urgent"
                )
        ) {
            return "HIGH";
        }

        if (
                "NEGATIVE".equalsIgnoreCase(sentiment)
                        &&
                containsAny(
                        text,
                        "payment",
                        "account",
                        "error",
                        "failed",
                        "support",
                        "refund"
                )
        ) {
            return "MEDIUM";
        }

        if (
                "NEGATIVE".equalsIgnoreCase(sentiment)
        ) {
            return "MEDIUM";
        }

        return "LOW";
    }

    /*
     * Routing
     */

    private static String determineOwner(
            String category
    ) {

        switch (category) {

            case "BILLING":
                return "Billing Support";

            case "ACCOUNT_ACCESS":
                return "Account Support";

            case "TECHNICAL":
                return "Technical Support";

            case "CUSTOMER_SUPPORT":
                return "Customer Success";

            case "FEATURE_REQUEST":
                return "Product Team";

            default:
                return "General Support";
        }
    }

    /*
     * Human-in-the-loop decision
     */

    private static boolean shouldEscalate(
            String sentiment,
            String severity
    ) {

        return "HIGH".equalsIgnoreCase(severity)
                ||
                (
                        "NEGATIVE".equalsIgnoreCase(sentiment)
                                &&
                        "MEDIUM".equalsIgnoreCase(severity)
                );
    }

    private static boolean containsAny(
            String text,
            String... keywords
    ) {

        for (String keyword : keywords) {

            if (text.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    private static String getEnvOrDefault(
            String name,
            String defaultValue
    ) {

        String value = System.getenv(name);

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return value;
    }
}