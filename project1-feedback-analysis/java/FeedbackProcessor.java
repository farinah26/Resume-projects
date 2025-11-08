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

    private static final String BUCKET_NAME = "farina-feedback-processed-20250929";
    private static final String CLEANED_PATH = "2025/10/cleaned-feedback/";
    private static final String PROCESSED_PATH = "2025/10/processed-feedback/";
    private static final String DYNAMO_TABLE = "FeedbackTable";
    private static final String SNS_TOPIC_ARN = "arn:aws:sns:us-east-1:512795167881:NegativeFeedbackAlerts";

    public static void main(String[] args) throws Exception {

        Region region = Region.US_EAST_1;

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
                .build();

        // List CSV files in cleaned folder
        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(BUCKET_NAME)
                .prefix(CLEANED_PATH)
                .build();

        for (S3Object obj : s3.listObjectsV2(listReq).contents()) {
            if (!obj.key().endsWith(".csv")) continue;

            System.out.println("Processing file: " + obj.key());

            GetObjectRequest getReq = GetObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(obj.key())
                    .build();

            try (InputStream s3InputStream = s3.getObject(getReq);
                 CSVParser parser = CSVFormat.DEFAULT
                         .withFirstRecordAsHeader()
                         .withIgnoreHeaderCase()
                         .parse(new InputStreamReader(s3InputStream))) {

                // Prepare CSV output in memory
                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(outStream),
                        CSVFormat.DEFAULT.withHeader("id", "feedback", "sentiment"));

                for (CSVRecord record : parser) {
                    String id = record.get("id");
                    String feedback = record.get("feedback");

                    // Call Comprehend
                    DetectSentimentResponse detectRes = comprehend.detectSentiment(
                            DetectSentimentRequest.builder()
                                    .text(feedback)
                                    .languageCode("en")
                                    .build()
                    );
                    String sentiment = detectRes.sentimentAsString();

                    // Write to CSV
                    printer.printRecord(id, feedback, sentiment);

                    // Save to DynamoDB
                    Map<String, AttributeValue> item = new HashMap<>();
                    item.put("FeedbackID", AttributeValue.builder().s(id).build());
                    item.put("FeedbackText", AttributeValue.builder().s(feedback).build());
                    item.put("Sentiment", AttributeValue.builder().s(sentiment).build());
                    item.put("ProcessedDate", AttributeValue.builder()
                            .s(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).build());
                    item.put("Status", AttributeValue.builder().s("SUCCESS").build());

                    PutItemRequest putReq = PutItemRequest.builder()
                            .tableName(DYNAMO_TABLE)
                            .item(item)
                            .build();
                    dynamoDb.putItem(putReq);

                    // Send SNS alert for negative sentiment
                    if ("NEGATIVE".equalsIgnoreCase(sentiment)) {
                        PublishRequest pubReq = PublishRequest.builder()
                                .topicArn(SNS_TOPIC_ARN)
                                .message("Negative feedback detected for ID: " + id)
                                .build();
                        snsClient.publish(pubReq);
                        System.out.println("SNS alert sent for ID: " + id);
                    }
                }

                printer.flush();

                // Upload processed CSV back to S3
                String outputKey = obj.key().replace("cleaned-feedback", "processed-feedback");
                s3.putObject(
                        PutObjectRequest.builder().bucket(BUCKET_NAME).key(outputKey).build(),
                        RequestBody.fromBytes(outStream.toByteArray())
                );

                System.out.println("Processed file uploaded to: " + outputKey);

            } catch (Exception e) {
                System.err.println("Error processing file " + obj.key() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("All files processed successfully!");
    }
}

