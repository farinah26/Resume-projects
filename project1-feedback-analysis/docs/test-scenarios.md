# Test Scenarios

These scenarios validate the customer-support workflow across data quality, routing, severity, ownership, recommended action, and human escalation.

> **Note:** Amazon Comprehend sentiment and confidence are model-generated and may vary slightly between runs. Category, severity, routing, recommended action, and escalation are determined by the Java business rules.

## Scenario 1 — Duplicate Billing Charge

**Input**

```text
I was charged twice and support still has not responded.
```

**Expected workflow**

| Field | Expected Result |
|---|---|
| Sentiment | Likely `NEGATIVE` |
| Category | `BILLING` |
| Severity | `HIGH` |
| Recommended Owner | `Billing Support` |
| Recommended Action | `Open priority case and request immediate human review` |
| Needs Escalation | `true` |

**Why**

The phrase `charged twice` triggers a high-severity billing rule and requires human review.

---

## Scenario 2 — Urgent Technical Failure

**Input**

```text
Urgent: the application crashes every time I try to upload a receipt.
```

**Expected workflow**

| Field | Expected Result |
|---|---|
| Sentiment | Likely `NEGATIVE` |
| Category | `TECHNICAL` |
| Severity | `HIGH` |
| Recommended Owner | `Technical Support` |
| Recommended Action | `Open priority case and request immediate human review` |
| Needs Escalation | `true` |

**Why**

`crashes` maps the issue to Technical Support while `urgent` explicitly triggers high severity.

---

## Scenario 3 — Account Lockout

**Input**

```text
I am locked out of my account and cannot access my profile.
```

**Expected workflow**

| Field | Expected Result |
|---|---|
| Sentiment | Likely `NEGATIVE` |
| Category | `ACCOUNT_ACCESS` |
| Severity | `HIGH` |
| Recommended Owner | `Account Support` |
| Recommended Action | `Open priority case and request immediate human review` |
| Needs Escalation | `true` |

**Why**

`account`, `locked out`, and `cannot access` trigger the account-access and high-severity rules.

---

## Scenario 4 — Feature Request

**Input**

```text
I wish there were a way to export reports as CSV.
```

**Expected workflow**

| Field | Expected Result |
|---|---|
| Sentiment | Likely `NEUTRAL` |
| Category | `FEATURE_REQUEST` |
| Severity | `LOW` |
| Recommended Owner | `Product Team` |
| Recommended Action | `Capture request for product review and trend analysis` |
| Needs Escalation | `false` |

**Why**

The request contains feature-oriented language without a high-severity condition.

---

## Scenario 5 — Positive Feedback

**Input**

```text
I love the new dashboard. It is much easier to use.
```

**Expected workflow**

| Field | Expected Result |
|---|---|
| Sentiment | Likely `POSITIVE` |
| Category | `GENERAL` |
| Severity | `LOW` |
| Recommended Owner | `General Support` |
| Recommended Action | `Monitor and include in customer feedback trends` |
| Needs Escalation | `false` |

**Why**

No operational issue keyword or high-severity condition is present.

---

## Scenario 6 — Customer Support Delay

**Input**

```text
I have been waiting for support and no representative has responded.
```

**Expected workflow**

| Field | Expected Result |
|---|---|
| Sentiment | Likely `NEGATIVE` |
| Category | `CUSTOMER_SUPPORT` |
| Severity | `MEDIUM` if Comprehend returns `NEGATIVE`; otherwise `LOW` |
| Recommended Owner | `Customer Success` |
| Recommended Action | `Assign to Customer Success for follow-up` |
| Needs Escalation | `true` when severity is `MEDIUM` and sentiment is `NEGATIVE` |

**Why**

Support-related keywords route the issue to Customer Success. Severity depends on the Comprehend sentiment result unless a high-severity keyword is present.

---

## Data Quality Scenarios

The AWS Glue ETL layer should also validate the following cases before downstream processing:

| Scenario | Expected ETL Behavior |
|---|---|
| Missing `id` column | Reject or skip the dataset |
| Missing feedback field | Reject or skip the dataset |
| Blank feedback text | Remove the record |
| Blank ID | Remove the record |
| Duplicate ID | Keep the first record and remove duplicates |
| Alternate feedback column such as `comment` | Normalize to `feedback` |
| Extra whitespace | Normalize whitespace before writing cleaned output |

---

## Validation Goal

The workflow should demonstrate that:

```text
Raw Feedback
→ Validated Data
→ AI Sentiment
→ Business Classification
→ Severity
→ Ownership
→ Recommended Action
→ Human Escalation
```

Each stage has a clear responsibility and can be validated independently.
