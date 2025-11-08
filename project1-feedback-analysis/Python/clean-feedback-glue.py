import sys
import boto3
import pandas as pd
from io import StringIO
from awsglue.utils import getResolvedOptions
from awsglue.context import GlueContext
from awsglue.job import Job
from pyspark.context import SparkContext

args = getResolvedOptions(sys.argv, ['JOB_NAME'])
bucket_name = "farina-feedback-processed-20250929"
input_prefix = "2025/10/cleaned-feedback/"
output_prefix = "2025/10/processed-feedback/"

# Initialize Glue context
sc = SparkContext()
glueContext = GlueContext(sc)
job = Job(glueContext)
job.init(args['JOB_NAME'], args)

# Initialize clients
s3 = boto3.client("s3")
comprehend = boto3.client("comprehend", region_name="us-east-1")

# List all cleaned CSV files
response = s3.list_objects_v2(Bucket=bucket_name, Prefix=input_prefix)
if "Contents" not in response:
    print("No files found under cleaned-feedback.")
    job.commit()
    sys.exit(0)

for obj in response["Contents"]:
    key = obj["Key"]
    if not key.endswith(".csv"):
        continue

    print(f"Processing: {key}")
    csv_obj = s3.get_object(Bucket=bucket_name, Key=key)
    body = csv_obj["Body"].read().decode("utf-8")

    # Read CSV into DataFrame
    df = pd.read_csv(StringIO(body))
    df.columns = [c.strip().lower() for c in df.columns]  # normalize headers

    # Ensure required columns exist
    if "id" not in df.columns or ("feedback" not in df.columns and "feedbacktext" not in df.columns):
        print(f"⚠️ Skipping {key}, missing feedback column.")
        continue

    text_col = "feedback" if "feedback" in df.columns else "feedbacktext"

    sentiments = []
    for text in df[text_col]:
        if isinstance(text, str) and text.strip():
            try:
                sentiment = comprehend.detect_sentiment(Text=text, LanguageCode="en")["Sentiment"]

                # 🧠 Neutral correction logic
                if any(word in text.lower() for word in ["average", "okay", "fine", "decent"]):
                    sentiment = "NEUTRAL"

            except Exception as e:
                print(f"Error on record: {e}")
                sentiment = "UNKNOWN"
        else:
            sentiment = "NEUTRAL"
        sentiments.append(sentiment)
        print(f"→ {text[:40]}... → {sentiment}")

    df["sentiment"] = sentiments

    # Write updated CSV back to S3
    csv_buffer = StringIO()
    df.to_csv(csv_buffer, index=False)
    output_key = key.replace("cleaned-feedback", "processed-feedback")

    s3.put_object(
        Bucket=bucket_name,
        Key=output_key,
        Body=csv_buffer.getvalue().encode("utf-8")
    )

    print(f"✅ Saved with sentiment → s3://{bucket_name}/{output_key}")

job.commit()
print("Sentiment processing completed successfully.")
