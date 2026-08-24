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
        DetectSentimentResponse sentiment