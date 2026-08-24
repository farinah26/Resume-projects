package com.farina.feedback;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;

import software.amazon.awssdk.services.comprehend.ComprehendClient;
import software.amazon.awssdk.services.comprehend.model.DetectSentimentRequest;
import software.amazon.awssdk.services.comprehend.model.DetectSentimentResponse;
import software.amazon.awssdk.services.comprehend.model.SentimentScore;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FeedbackProcessor {

    private static final Region REGION = Region.US_EAST_1;

    private static final String DEFAULT_BUCKET =
            getEnvOrDefault("FEEDBACK_BUCKET", "your-feedback-bucket");

    private static final String CLEANED_PATH =
            getEnvOrDefault("CLEANED_PATH", "cleaned-feedback/");

    private static final String PROCESSED_PATH =
            getEnvOrDefault("PROCESSED_PATH", "processed-feedback/");

    private static final String DYNAMO_TABLE =
            getEnvOrDefault("DYNAMO_TABLE", "FeedbackTable");

    private static final String SNS_TOPIC_ARN =
            getEnvOrDefault("SNS_TOPIC_ARN", "");

    /*
     * Reusable AWS clients.
     *
     * In Lambda, these can be reused across warm invocations instead
     * of creating new clients for every request.
     */
    private static final S3Client s3 =
            S3Client.builder()
                    .region(REGION)
                    .credentialsProvider(
                            DefaultCredentialsProvider.create()
                    )
                    .build();

    private static final ComprehendClient comprehend =
            ComprehendClient.builder()
                    .region(REGION)
                    .credentialsProvider(
                            DefaultCredentialsProvider.create()
                    )
                    .build();

    private static final DynamoDbClient dynamoDb =
            DynamoDbClient.builder()
                    .region(REGION)
                    .credentialsProvider(
                            DefaultCredentialsProvider.create()
                    )
                    .build();

    private static final SnsClient sns =
            SnsClient.builder()
                    .region(REGION)
                    .credentialsProvider(
                            DefaultCredentialsProvider.create()
                    )
                    .build();


    /*
     * Local/manual entry point.
     *
     * This allows the same processor to still be run manually
     * outside Lambda if needed.
     */
    public static void main(String[] args) {

        try {

            ListObjectsV2Request request =
                    ListObjectsV2Request.builder()
                            .bucket(DEFAULT_BUCKET)
                            .prefix(CLEANED_PATH)
                            .build();

            for (S3Object object :
                    s3.listObjectsV2(request).contents()) {

                if (!object.key().toLowerCase().endsWith(".csv")) {
                    continue;
                }

                processS3Object(
                        DEFAULT_BUCKET,
                        object.key()
                );
            }

            System.out.println(
                    "All cleaned feedback files processed."
            );

        } catch (Exception e) {

            System.err.println(
                    "Application processing failure: "
                            + e.getMessage()
            );

            e.printStackTrace();
        }
    }


    /*
     * Public entry point used by LambdaHandler.
     */
    public static void processS3Object(
            String bucket,
            String key
    ) {

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException(
                    "S3 bucket cannot be empty."
            );
        }

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "S3 object key cannot be empty."
            );
        }

        if (!key.toLowerCase().endsWith(".csv")) {

            System.out.println(
                    "Skipping non-CSV object: " + key
            );

            return;
        }

        System.out.println(
                "Processing cleaned feedback file: "
                        + bucket
                        + "/"
                        + key
        );

        GetObjectRequest request =
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();

        try (
                InputStream inputStream =
                        s3.getObject(request);

                CSVParser parser =
                        CSVFormat.DEFAULT
                                .withFirstRecordAsHeader()
                                .withIgnoreHeaderCase()
                                .withTrim()
                                .parse(
                                        new InputStreamReader(
                                                inputStream
                                        )
                                )
        ) {

            ByteArrayOutputStream outputStream =
                    new ByteArrayOutputStream();

            CSVPrinter printer =
                    new CSVPrinter(
                            new OutputStreamWriter(
                                    outputStream
                            ),
                            CSVFormat.DEFAULT.withHeader(
                                    "id",
                                    "feedback",
                                    "sentiment",
                                    "confidence",
                                    "category",
                                    "severity",
                                    "recommended_owner",
                                    "recommended_action",
                                    "needs_escalation",
                                    "processed_date"
                            )
                    );

            int processedCount = 0;
            int failedCount = 0;
            int escalationCount = 0;

            for (CSVRecord record : parser) {

                try {

                    boolean escalated =
                            processFeedbackRecord(
                                    record,
                                    printer
                            );

                    processedCount++;

                    if (escalated) {
                        escalationCount++;
                    }

                } catch (Exception recordException) {

                    failedCount++;

                    System.err.println(
                            "Failed to process CSV record "
                                    + record.getRecordNumber()
                                    + ": "
                                    + recordException.getMessage()
                    );
                }
            }

            printer.flush();

            String fileName =
                    key.substring(
                            key.lastIndexOf("/") + 1
                    );

            String outputKey =
                    PROCESSED_PATH
                            .replaceAll("/+$", "")
                            + "/"
                            + fileName;

            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(outputKey)
                            .contentType("text/csv")
                            .build(),
                    RequestBody.fromBytes(
                            outputStream.toByteArray()
                    )
            );

            System.out.println(
                    "Processed file saved to: s3://"
                            + bucket
                            + "/"
                            + outputKey
            );

            System.out.println(
                    "Processing summary"
                            + " | successful="
                            + processedCount
                            + " | failed="
                            + failedCount
                            + " | escalations="
                            + escalationCount
            );

        } catch (Exception e) {

            throw new RuntimeException(
                    "Unable to process S3 object "
                            + key
                            + ": "
                            + e.getMessage(),
                    e
            );
        }
    }


    private static boolean processFeedbackRecord(
            CSVRecord record,
            CSVPrinter printer
    ) throws Exception {

        String id =
                getRequiredField(
                        record,
                        "id"
                );

        String feedback =
                getRequiredField(
                        record,
                        "feedback"
                );

        /*
         * AI / NLP layer
         */
        DetectSentimentResponse sentimentResponse =
                comprehend.detectSentiment(
                        DetectSentimentRequest.builder()
                                .text(feedback)
                                .languageCode("en")
                                .build()
                );

        String sentiment =
                sentimentResponse.sentimentAsString();

        double confidence =
                getSentimentConfidence(
                        sentiment,
                        sentimentResponse.sentimentScore()
                );

        /*
         * Deterministic business rules
         */
        String category =
                categorizeFeedback(feedback);

        String severity =
                determineSeverity(
                        feedback,
                        sentiment
                );

        String recommendedOwner =
                determineOwner(category);

        String recommendedAction =
                determineRecommendedAction(
                        category,
                        severity
                );

        boolean needsEscalation =
                shouldEscalate(
                        sentiment,
                        severity
                );

        String processedDate =
                DateTimeFormatter.ISO_INSTANT.format(
                        Instant.now()
                );

        /*
         * Processed CSV output
         */
        printer.printRecord(
                id,
                feedback,
                sentiment,
                String.format(
                        Locale.US,
                        "%.4f",
                        confidence
                ),
                category,
                severity,
                recommendedOwner,
                recommendedAction,
                needsEscalation,
                processedDate
        );

        /*
         * Persist enriched record to DynamoDB
         */
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
                "SentimentConfidence",
                AttributeValue.builder()
                        .n(
                                String.valueOf(
                                        confidence
                                )
                        )
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
                "RecommendedAction",
                AttributeValue.builder()
                        .s(recommendedAction)
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

        /*
         * Human-in-the-loop escalation
         */
        if (
                needsEscalation
                        &&
                SNS_TOPIC_ARN != null
                        &&
                !SNS_TOPIC_ARN.isBlank()
        ) {

            sendEscalationAlert(
                    id,
                    feedback,
                    sentiment,
                    confidence,
                    category,
                    severity,
                    recommendedOwner,
                    recommendedAction
            );
        }

        System.out.println(
                "Processed feedback "
                        + id
                        + " | sentiment="
                        + sentiment
                        + " | confidence="
                        + String.format(
                                Locale.US,
                                "%.2f",
                                confidence
                        )
                        + " | category="
                        + category
                        + " | severity="
                        + severity
                        + " | owner="
                        + recommendedOwner
                        + " | escalate="
                        + needsEscalation
        );

        return needsEscalation;
    }


    private static void sendEscalationAlert(
            String id,
            String feedback,
            String sentiment,
            double confidence,
            String category,
            String severity,
            String recommendedOwner,
            String recommendedAction
    ) {

        String message =
                "CUSTOMER FEEDBACK ESCALATION\n\n"
                        + "Feedback ID: "
                        + id
                        + "\n"
                        + "Sentiment: "
                        + sentiment
                        + "\n"
                        + "Confidence: "
                        + String.format(
                                Locale.US,
                                "%.1f%%",
                                confidence * 100
                        )
                        + "\n"
                        + "Category: "
                        + category
                        + "\n"
                        + "Severity: "
                        + severity
                        + "\n"
                        + "Recommended Owner: "
                        + recommendedOwner
                        + "\n"
                        + "Recommended Action: "
                        + recommendedAction
                        + "\n\n"
                        + "Customer Feedback:\n"
                        + feedback;

        sns.publish(
                PublishRequest.builder()
                        .topicArn(
                                SNS_TOPIC_ARN
                        )
                        .subject(
                                "Customer Feedback Escalation"
                        )
                        .message(message)
                        .build()
        );

        System.out.println(
                "SNS escalation sent for Feedback ID: "
                        + id
        );
    }


    /*
     * Extract Comprehend confidence for the
     * sentiment actually selected by the model.
     */
    private static double getSentimentConfidence(
            String sentiment,
            SentimentScore score
    ) {

        if (score == null || sentiment == null) {
            return 0.0;
        }

        switch (
                sentiment.toUpperCase(
                        Locale.ROOT
                )
        ) {

            case "POSITIVE":
                return safeFloat(
                        score.positive()
                );

            case "NEGATIVE":
                return safeFloat(
                        score.negative()
                );

            case "NEUTRAL":
                return safeFloat(
                        score.neutral()
                );

            case "MIXED":
                return safeFloat(
                        score.mixed()
                );

            default:
                return 0.0;
        }
    }


    private static double safeFloat(
            Float value
    ) {

        return value == null
                ? 0.0
                : value.doubleValue();
    }


    /*
     * Customer issue categorization
     */
    private static String categorizeFeedback(
            String feedback
    ) {

        String text =
                feedback.toLowerCase(
                        Locale.ROOT
                );

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

        String text =
                feedback.toLowerCase(
                        Locale.ROOT
                );

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
                "NEGATIVE".equalsIgnoreCase(
                        sentiment
                )
        ) {
            return "MEDIUM";
        }

        return "LOW";
    }


    /*
     * Recommended support team
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
     * Recommended operational action
     */
    private static String determineRecommendedAction(
            String category,
            String severity
    ) {

        if (
                "HIGH".equalsIgnoreCase(
                        severity
                )
        ) {
            return "Open priority case and request immediate human review";
        }

        switch (category) {

            case "BILLING":
                return "Review payment history and follow up with the customer";

            case "ACCOUNT_ACCESS":
                return "Verify account status and initiate account recovery support";

            case "TECHNICAL":
                return "Create a technical support case with the reported error context";

            case "CUSTOMER_SUPPORT":
                return "Assign to Customer Success for follow-up";

            case "FEATURE_REQUEST":
                return "Capture request for product review and trend analysis";

            default:
                return "Monitor and include in customer feedback trends";
        }
    }


    /*
     * Human-in-the-loop decision
     */
    private static boolean shouldEscalate(
            String sentiment,
            String severity
    ) {

        return "HIGH".equalsIgnoreCase(
                severity
        )
                ||
                (
                        "NEGATIVE".equalsIgnoreCase(
                                sentiment
                        )
                                &&
                        "MEDIUM".equalsIgnoreCase(
                                severity
                        )
                );
    }


    private static boolean containsAny(
            String text,
            String... keywords
    ) {

        for (String keyword : keywords) {

            if (
                    text.contains(
                            keyword
                    )
            ) {
                return true;
            }
        }

        return false;
    }


    private static String getRequiredField(
            CSVRecord record,
            String field
    ) {

        if (
                !record.isMapped(field)
        ) {
            throw new IllegalArgumentException(
                    "Required CSV column missing: "
                            + field
            );
        }

        String value =
                record.get(field);

        if (
                value == null
                        ||
                value.trim().isEmpty()
        ) {
            throw new IllegalArgumentException(
                    "Required field is empty: "
                            + field
            );
        }

        return value.trim();
    }


    private static String getEnvOrDefault(
            String name,
            String defaultValue
    ) {

        String value =
                System.getenv(name);

        if (
                value == null
                        ||
                value.trim().isEmpty()
        ) {
            return defaultValue;
        }

        return value.trim();
    }
}