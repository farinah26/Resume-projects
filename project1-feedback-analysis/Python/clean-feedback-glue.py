import sys
import boto3
import pandas as pd

from io import StringIO
from awsglue.utils import getResolvedOptions
from awsglue.context import GlueContext
from awsglue.job import Job
from pyspark.context import SparkContext


# ---------------------------------------------------------
# Glue job configuration
# ---------------------------------------------------------

args = getResolvedOptions(
    sys.argv,
    ["JOB_NAME"]
)

# Environment-specific configuration
# These can later be converted to Glue job arguments.
BUCKET_NAME = "farina-feedback-processed-20250929"
INPUT_PREFIX = "2025/10/raw-feedback/"
OUTPUT_PREFIX = "2025/10/cleaned-feedback/"


# ---------------------------------------------------------
# Initialize AWS Glue
# ---------------------------------------------------------

sc = SparkContext()
glue_context = GlueContext(sc)

job = Job(glue_context)
job.init(args["JOB_NAME"], args)

s3 = boto3.client("s3")


# ---------------------------------------------------------
# Job metrics
# ---------------------------------------------------------

files_processed = 0
input_records = 0
valid_records = 0
rejected_records = 0
duplicate_records = 0


# ---------------------------------------------------------
# Data preparation helpers
# ---------------------------------------------------------

def normalize_columns(df):
    """
    Normalize column names so feedback from different
    source systems follows the same schema.
    """

    df.columns = [
        str(column)
        .strip()
        .lower()
        .replace(" ", "_")
        for column in df.columns
    ]

    return df


def normalize_feedback_column(df):
    """
    Support common feedback column names from different
    sources and convert them to the standard 'feedback'.
    """

    possible_columns = [
        "feedback",
        "feedbacktext",
        "feedback_text",
        "comment",
        "comments",
        "review",
        "customer_feedback"
    ]

    for column in possible_columns:

        if column in df.columns:

            if column != "feedback":
                df = df.rename(
                    columns={column: "feedback"}
                )

            return df

    return df


def validate_schema(df):
    """
    Validate the minimum fields required by the
    downstream customer-feedback processor.
    """

    required_columns = [
        "id",
        "feedback"
    ]

    return [
        column
        for column in required_columns
        if column not in df.columns
    ]


def clean_feedback(df):
    """
    Remove invalid records, normalize feedback text,
    remove duplicate IDs and attach ETL metadata.
    """

    global rejected_records
    global duplicate_records

    # Normalize required values
    df["id"] = (
        df["id"]
        .astype(str)
        .str.strip()
    )

    df["feedback"] = (
        df["feedback"]
        .astype(str)
        .str.strip()
    )

    # Identify invalid rows
    invalid_mask = (
        df["id"].eq("")
        | df["feedback"].eq("")
        | df["id"].str.lower().eq("nan")
        | df["feedback"].str.lower().eq("nan")
    )

    invalid_count = int(
        invalid_mask.sum()
    )

    rejected_records += invalid_count

    df = df[
        ~invalid_mask
    ].copy()

    # Remove duplicate feedback IDs
    before_dedup = len(df)

    df = df.drop_duplicates(
        subset=["id"],
        keep="first"
    )

    duplicates = (
        before_dedup - len(df)
    )

    duplicate_records += duplicates

    # Normalize whitespace in customer comments
    df["feedback"] = (
        df["feedback"]
        .str.replace(
            r"\s+",
            " ",
            regex=True
        )
        .str.strip()
    )

    # Metadata for downstream processing
    df["processing_status"] = "VALIDATED"
    df["source"] = "customer_feedback"

    return df


# ---------------------------------------------------------
# Discover input files
# ---------------------------------------------------------

response = s3.list_objects_v2(
    Bucket=BUCKET_NAME,
    Prefix=INPUT_PREFIX
)

if "Contents" not in response:

    print(
        f"No CSV files found under "
        f"s3://{BUCKET_NAME}/{INPUT_PREFIX}"
    )

    job.commit()
    sys.exit(0)


# ---------------------------------------------------------
# Process feedback files
# ---------------------------------------------------------

for obj in response["Contents"]:

    key = obj["Key"]

    if not key.lower().endswith(".csv"):
        continue

    files_processed += 1

    print(
        f"Processing: {key}"
    )

    try:

        csv_obj = s3.get_object(
            Bucket=BUCKET_NAME,
            Key=key
        )

        body = (
            csv_obj["Body"]
            .read()
            .decode("utf-8")
        )

        df = pd.read_csv(
            StringIO(body)
        )

        input_records += len(df)

        # Normalize incoming data
        df = normalize_columns(df)
        df = normalize_feedback_column(df)

        # Validate required schema
        missing_columns = validate_schema(df)

        if missing_columns:

            print(
                f"Skipping {key}. "
                f"Missing required columns: "
                f"{missing_columns}"
            )

            rejected_records += len(df)

            continue

        # Clean and standardize records
        cleaned_df = clean_feedback(df)

        valid_records += len(
            cleaned_df
        )

        # Preserve original file name
        file_name = key.split("/")[-1]

        output_key = (
            f"{OUTPUT_PREFIX.rstrip('/')}/"
            f"{file_name}"
        )

        csv_buffer = StringIO()

        cleaned_df.to_csv(
            csv_buffer,
            index=False
        )

        s3.put_object(
            Bucket=BUCKET_NAME,
            Key=output_key,
            Body=csv_buffer
            .getvalue()
            .encode("utf-8")
        )

        print(
            f"Validated data saved to "
            f"s3://{BUCKET_NAME}/{output_key}"
        )

    except Exception as error:

        print(
            f"Failed processing {key}: "
            f"{str(error)}"
        )


# ---------------------------------------------------------
# Operational summary
# ---------------------------------------------------------

print("")
print(
    "========== GLUE ETL SUMMARY =========="
)

print(
    f"Files processed:   {files_processed}"
)

print(
    f"Input records:     {input_records}"
)

print(
    f"Valid records:     {valid_records}"
)

print(
    f"Rejected records:  {rejected_records}"
)

print(
    f"Duplicate records: {duplicate_records}"
)

print(
    "======================================"
)

print(
    "Feedback validation and cleaning completed."
)

job.commit()