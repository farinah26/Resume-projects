package com.farina.feedback;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class LambdaHandler
        implements RequestHandler<S3Event, String> {

    private static final String CLEANED_PATH =
            getEnvOrDefault(
                    "CLEANED_PATH",
                    "cleaned-feedback/"
            );

    @Override
    public String handleRequest(
            S3Event event,
            Context context
    ) {

        if (
                event == null
                        ||
                event.getRecords() == null
                        ||
                event.getRecords().isEmpty()
        ) {

            context.getLogger().log(
                    "No S3 records received.\n"
            );

            return "No records received.";
        }

        int processed = 0;
        int skipped = 0;
        int failed = 0;

        for (
                var record :
                event.getRecords()
        ) {

            try {

                String bucket =
                        record
                                .getS3()
                                .getBucket()
                                .getName();

                String encodedKey =
                        record
                                .getS3()
                                .getObject()
                                .getKey();

                /*
                 * S3 event keys are URL encoded.
                 */
                String key =
                        URLDecoder.decode(
                                encodedKey,
                                StandardCharsets.UTF_8
                        );

                context.getLogger().log(
                        "Received S3 event: "
                                + bucket
                                + "/"
                                + key
                                + "\n"
                );

                /*
                 * Protect against accidental triggers from
                 * processed output or unrelated files.
                 */
                if (
                        !key.startsWith(
                                CLEANED_PATH
                        )
                ) {

                    context.getLogger().log(
                            "Skipping object outside cleaned feedback path: "
                                    + key
                                    + "\n"
                    );

                    skipped++;

                    continue;
                }

                if (
                        !key
                                .toLowerCase()
                                .endsWith(".csv")
                ) {

                    context.getLogger().log(
                            "Skipping non-CSV object: "
                                    + key
                                    + "\n"
                    );

                    skipped++;

                    continue;
                }

                /*
                 * Delegate all business processing to
                 * FeedbackProcessor.
                 */
                FeedbackProcessor.processS3Object(
                        bucket,
                        key
                );

                processed++;

                context.getLogger().log(
                        "Successfully processed: "
                                + key
                                + "\n"
                );

            } catch (Exception e) {

                failed++;

                context.getLogger().log(
                        "Failed processing S3 event: "
                                + e.getMessage()
                                + "\n"
                );
            }
        }

        String result =
                "Lambda processing completed"
                        + " | processed="
                        + processed
                        + " | skipped="
                        + skipped
                        + " | failed="
                        + failed;

        context.getLogger().log(
                result + "\n"
        );

        /*
         * If every attempted record failed, surface an
         * exception so Lambda monitoring can detect it.
         */
        if (
                processed == 0
                        &&
                failed > 0
        ) {
            throw new RuntimeException(
                    "All feedback processing attempts failed."
            );
        }

        return result;
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