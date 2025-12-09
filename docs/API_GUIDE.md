# StarExec API Guide

This guide documents how to interact with StarExec programmatically via HTTP requests.

**Base URL**: `https://starexec.ccs.miami.edu/starexec/`

---

## Table of Contents

1. [Authentication](#authentication)
2. [Quick Job Submission](#quick-job-submission)
3. [Job XML Upload](#job-xml-upload)
4. [Common Issues & Troubleshooting](#common-issues--troubleshooting)

---

## Authentication

StarExec uses **cookie-based session authentication**. You must:

1. Obtain a session ID (JSESSIONID)
2. Submit credentials via form-based login
3. Maintain the session cookie for all subsequent requests

### Login Flow (3 Steps)

#### Step 1: Get Initial Session

```bash
# Get initial JSESSIONID
curl -c cookies.txt -b cookies.txt \
  -A "StarExecCommand" \
  "https://starexec.ccs.miami.edu/starexec/secure/index.jsp"
```

#### Step 2: Submit Credentials

```bash
# Login with credentials (form-based authentication)
curl -c cookies.txt -b cookies.txt \
  -A "StarExecCommand" \
  -L \
  -d "j_username=YOUR_EMAIL&j_password=YOUR_PASSWORD&cookieexists=false" \
  "https://starexec.ccs.miami.edu/starexec/secure/j_security_check"
```

#### Step 3: Verify Login

```bash
# Check if logged in (should return "true")
curl -c cookies.txt -b cookies.txt \
  -A "StarExecCommand" \
  "https://starexec.ccs.miami.edu/starexec/services/session/logged-in"
```

**Expected response**: `true` (plain text)

### Important Notes

- **User-Agent**: Set to `StarExecCommand` or `Apache-HttpClient` for proper API detection
- **Cookies**: The JSESSIONID cookie must be sent with ALL subsequent requests
- **Cookie Persistence**: Use a cookie jar (`-c cookies.txt -b cookies.txt` in curl)

---

## Quick Job Submission

**Endpoint**: `POST /starexec/secure/add/job`  
**Content-Type**: `application/x-www-form-urlencoded`

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `sid` | integer | Space ID where the job will be created |
| `name` | string | Job name (1-255 chars, alphanumeric + `_-.+^=,!?:$%#@ `) |
| `runChoice` | string | Must be `"quickJob"` for quick jobs |
| `solver` | integer | Solver ID |
| `bench` | string | Benchmark content (the actual problem text) |
| `benchName` | string | Name for the uploaded benchmark |
| `benchProcess` | integer | Benchmark processor ID (use `-1` for none) |
| `queue` | integer | Queue ID |
| `seed` | long | Random seed (use `0` for default) |
| `pause` | string | `"yes"` or `"no"` - start job paused? |
| `benchmarkingFramework` | string | **REQUIRED**: `"RUNSOLVER"` or `"BENCHEXEC"` |

### Optional Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `desc` | string | Job description |
| `preProcess` | integer | Pre-processor ID (`-1` for none) |
| `postProcess` | integer | Post-processor ID (`-1` for none) |
| `wallclockTimeout` | integer | Wallclock timeout in seconds |
| `cpuTimeout` | integer | CPU timeout in seconds |
| `maxMem` | double | Maximum memory in gigabytes |

### Example: Quick Job via curl

```bash
curl -X POST \
  -c cookies.txt -b cookies.txt \
  -A "StarExecCommand" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "sid=12345" \
  -d "name=TestJob-$(date +%Y%m%d-%H%M%S)" \
  -d "runChoice=quickJob" \
  -d "solver=3894" \
  -d "bench=fof(test, conjecture, p | ~p)." \
  -d "benchName=test-benchmark" \
  -d "benchProcess=-1" \
  -d "queue=1" \
  -d "seed=0" \
  -d "pause=no" \
  -d "preProcess=-1" \
  -d "postProcess=-1" \
  -d "wallclockTimeout=60" \
  -d "cpuTimeout=60" \
  -d "maxMem=1.0" \
  -d "benchmarkingFramework=RUNSOLVER" \
  "https://starexec.ccs.miami.edu/starexec/secure/add/job"
```

### Success Response

- **HTTP 302** (Redirect) with `New_ID` cookie containing the job ID
- Redirects to job details page

### Error Response

- **HTTP 400** with error message in `STATUS_MESSAGE_COOKIE` cookie
- Common error: `"You do not have permission to add jobs in this space"`

---

## Job XML Upload

For more complex jobs, use the XML upload endpoint.

**Endpoint**: `POST /starexec/secure/upload/jobXML`  
**Content-Type**: `multipart/form-data`

### Required Form Fields

| Field | Type | Description |
|-------|------|-------------|
| `space` | integer | Space ID for the job |
| `f` | file | Archive file (.zip, .tar, or .tgz) containing XML |

### XML Schema

The XML file must follow the StarExec batch job schema.

#### Minimal Job XML Example

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tns:Jobs xmlns:tns="https://www.starexec.org/starexec/public/batchJobSchema.xsd"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="https://www.starexec.org/starexec/public/batchJobSchema.xsd batchJobSchema.xsd">

  <Job name="MyTestJob">
    <JobAttributes>
      <description value="Job created via XML upload"/>
      <queue-id value="1"/>
      <cpu-timeout value="60"/>
      <wallclock-timeout value="60"/>
      <mem-limit value="1.0"/>
      <bench-framework value="runsolver"/>
    </JobAttributes>

    <!-- Single job pair using existing benchmark and config -->
    <JobPair
        config-id="401022"
        bench-id="4281293"
        job-space-path="."/>
  </Job>

</tns:Jobs>
```

#### JobAttributes Elements

| Element | Required | Description |
|---------|----------|-------------|
| `queue-id` | Yes | Queue ID |
| `cpu-timeout` | Yes | CPU timeout in seconds |
| `wallclock-timeout` | Yes | Wallclock timeout in seconds |
| `mem-limit` | Yes | Memory limit in gigabytes |
| `description` | No | Job description |
| `start-paused` | No | `true` or `false` (default: false) |
| `seed` | No | Random seed |
| `postproc-id` | No | Post-processor ID |
| `preproc-id` | No | Pre-processor ID |
| `bench-framework` | No | `runsolver` or `benchexec` |

#### JobPair Attributes

| Attribute | Required | Description |
|-----------|----------|-------------|
| `config-id` | Yes | Configuration ID |
| `bench-id` | Yes | Benchmark ID |
| `job-space-path` | No | Path in job space hierarchy (use `.` for root) |
| `job-space-id` | No | Alternative to path |
| `bench-name` | No | Override benchmark name |
| `solver-id` | No | Solver ID (optional, inferred from config) |

### Example: Job XML Upload via curl

```bash
# Create a zip file containing the job XML
zip job.zip job.xml

# Upload
curl -X POST \
  -c cookies.txt -b cookies.txt \
  -A "StarExecCommand" \
  -F "space=12345" \
  -F "f=@job.zip" \
  "https://starexec.ccs.miami.edu/starexec/secure/upload/jobXML"
```

---

## Common Issues & Troubleshooting

### Issue: "You do not have permission to add jobs in this space"

**Cause**: Session not properly authenticated or wrong user ID being detected.

**Solutions**:

1. **Verify session is active**:
   ```bash
   curl -b cookies.txt \
     "https://starexec.ccs.miami.edu/starexec/services/session/logged-in"
   ```
   Should return `true`.

2. **Check User-Agent header**: Must be `StarExecCommand` or include `Apache-HttpClient`

3. **Ensure cookies are being sent**: Use `-b cookies.txt` with every request

4. **Re-login if session expired**: Sessions expire after 60 minutes of inactivity

### Issue: Missing `benchmarkingFramework` Parameter

**Symptom**: Job creation fails with validation error.

**Solution**: Always include `benchmarkingFramework=RUNSOLVER` or `benchmarkingFramework=BENCHEXEC` in quick job requests.

### Issue: HTTP 400 on Job XML Upload

**Possible causes**:
- Archive format not .zip, .tar, or .tgz
- XML schema validation failure
- Missing required fields in XML

**Debug**: Check the `STATUS_MESSAGE_COOKIE` in the response headers.

### Issue: Invalid Benchmark/Solver/Config IDs

**Solution**: Verify IDs exist and you have permission to access them:
- Benchmark: Check at `secure/details/benchmark.jsp?id=XXX`
- Solver: Check at `secure/details/solver.jsp?id=XXX`
- Config: Check at `secure/details/configuration.jsp?id=XXX`

---

## Complete Working Example (Bash Script)

```bash
#!/bin/bash

# Configuration
STAREXEC_URL="https://starexec.ccs.miami.edu/starexec"
USERNAME="your-email@example.com"
PASSWORD="your-password"
SPACE_ID="12345"
SOLVER_ID="3894"
QUEUE_ID="1"

COOKIES="cookies.txt"

# Clean up old cookies
rm -f "$COOKIES"

echo "=== Step 1: Getting initial session ==="
curl -s -c "$COOKIES" -b "$COOKIES" \
  -A "StarExecCommand" \
  "$STAREXEC_URL/secure/index.jsp" > /dev/null

echo "=== Step 2: Logging in ==="
curl -s -c "$COOKIES" -b "$COOKIES" \
  -A "StarExecCommand" \
  -L \
  -d "j_username=$USERNAME&j_password=$PASSWORD&cookieexists=false" \
  "$STAREXEC_URL/secure/j_security_check" > /dev/null

echo "=== Step 3: Verifying login ==="
LOGGED_IN=$(curl -s -b "$COOKIES" \
  -A "StarExecCommand" \
  "$STAREXEC_URL/services/session/logged-in")

if [ "$LOGGED_IN" != "true" ]; then
  echo "ERROR: Login failed!"
  exit 1
fi
echo "Login successful!"

echo "=== Step 4: Submitting quick job ==="
RESPONSE=$(curl -s -D - -c "$COOKIES" -b "$COOKIES" \
  -A "StarExecCommand" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "sid=$SPACE_ID" \
  --data-urlencode "name=APITest-$(date +%Y%m%d-%H%M%S)" \
  --data-urlencode "runChoice=quickJob" \
  --data-urlencode "solver=$SOLVER_ID" \
  --data-urlencode "bench=fof(test, conjecture, p | ~p)." \
  --data-urlencode "benchName=api-test-bench" \
  --data-urlencode "benchProcess=-1" \
  --data-urlencode "queue=$QUEUE_ID" \
  --data-urlencode "seed=0" \
  --data-urlencode "pause=no" \
  --data-urlencode "preProcess=-1" \
  --data-urlencode "postProcess=-1" \
  --data-urlencode "wallclockTimeout=60" \
  --data-urlencode "cpuTimeout=60" \
  --data-urlencode "maxMem=1.0" \
  --data-urlencode "benchmarkingFramework=RUNSOLVER" \
  "$STAREXEC_URL/secure/add/job")

# Extract job ID from New_ID cookie
JOB_ID=$(echo "$RESPONSE" | grep -i "Set-Cookie.*New_ID" | sed 's/.*New_ID=\([0-9]*\).*/\1/')

if [ -n "$JOB_ID" ]; then
  echo "Job created successfully! Job ID: $JOB_ID"
  echo "View at: $STAREXEC_URL/secure/details/job.jsp?id=$JOB_ID"
else
  echo "Job creation may have failed. Check response:"
  echo "$RESPONSE" | head -50
fi

echo "=== Step 5: Logging out ==="
curl -s -X POST -b "$COOKIES" \
  -A "StarExecCommand" \
  "$STAREXEC_URL/services/session/logout" > /dev/null

echo "Done!"
```

---

## API Endpoints Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/secure/index.jsp` | GET | Homepage (for initial session) |
| `/secure/j_security_check` | POST | Login authentication |
| `/services/session/logged-in` | GET | Check login status |
| `/services/session/logout` | POST | Logout |
| `/secure/add/job` | POST | Create quick job |
| `/secure/upload/jobXML` | POST | Upload job XML |
| `/secure/upload/benchmarks` | POST | Upload benchmarks |
| `/secure/upload/solvers` | POST | Upload solvers |
| `/services/delete/{type}` | POST | Delete primitives |

---

## Notes for StarExec Miami Instance

- **Base URL**: `https://starexec.ccs.miami.edu/starexec/`
- **Session timeout**: 60 minutes
- **Max upload size**: 50MB for job XML
- **Supported archive formats**: .zip, .tar, .tgz
