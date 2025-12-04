Hybrid AWS Customer Feedback Analysis Platform (Please view this document in Code format.)
Overview

This project is a serverless AWS solution to collect, process, and analyze customer feedback from multiple sources such as CSV files, surveys, and forms.

It leverages AWS S3, Lambda, DynamoDB, and QuickSight, with lightweight Java scripts to automate sentiment analysis and optional notifications. The system provides real-time insights for business and technical teams while minimizing coding effort.

Architecture
S3 (raw feedback) → Lambda (trigger/automation) → AWS Comprehend (sentiment analysis)
→ DynamoDB (store processed feedback) → QuickSight (visual dashboards)
→ SNS (optional alerts)


S3: Stores raw and processed feedback files.

Lambda: Triggers automation tasks like running sentiment analysis or sending alerts.

AWS Comprehend: Detects sentiment (positive/negative/neutral) and extracts key phrases.

DynamoDB: Stores cleaned, processed, and analyzed feedback.

QuickSight: Visualizes trends, sentiment distribution, and common topics.

SNS (optional): Sends notifications for negative feedback automatically.

Key Features:

Aggregates feedback from multiple sources.

Performs sentiment analysis with minimal Java automation.

Generates QuickSight dashboards for actionable insights.

Mostly AWS serverless tools — very little coding required.

Folder Structure:
Hybrid-AWS-Feedback-Analysis/

│
├─ data/
│   ├─ raw/           # Sample CSVs (e.g., feedback_input.csv)
│   └─ processed/     # Output from Glue ETL (optional)
│
├─ java/              # Minimal automation scripts
│   ├─ TriggerComprehend.java
│   └─ SNSAlert.java
│
├─ docs/
│   ├─ architecture.png   # Optional diagram of the workflow
│   └─ screenshots/       # Glue jobs, Comprehend output, QuickSight dashboards
│
├─ pom.xml               # Maven dependencies for Java scripts
└─ README.md

Getting Started

Upload sample feedback CSVs to data/raw.

Run Glue ETL via AWS console to clean and standardize feedback.

Run AWS Comprehend on cleaned feedback to detect sentiment and key phrases.

Optional: use lightweight Java scripts to automate sentiment analysis or trigger SNS alerts.

Store processed feedback in DynamoDB.

Build dashboards in QuickSight to visualize sentiment trends, key phrases, and overall feedback distribution.

Skills Demonstrated

AWS Cloud Architecture: S3, Lambda, Glue, Comprehend, DynamoDB, QuickSight, SNS

Lightweight Java Automation for triggering jobs and sending alerts

ETL, sentiment analysis, and data visualization

Designing scalable, maintainable serverless solutions

Turning raw customer data into actionable insights
