# Deploying the VE Compliance Engine on AWS: Beginner Guide

Goal: a working, demo-able deployment you can describe on a resume, built with roughly $200 of credits.

Console labels change now and then. If a button name differs, search the AWS docs for the service name, the idea stays the same.

---

## 0. Read this first: what will and will not work

Your backend launches `powershell.exe` and uses Windows CIM/WinRM and DPAPI (see DECISIONS.md D2). So:

| Part | Where it runs |
|---|---|
| Backend (jar + PowerShell agents) | Windows Server EC2 |
| Database | RDS PostgreSQL 16 |
| Frontend (Next.js) | Linux EC2 with Docker |
| A real device to check | A second Windows EC2 (the "target") |

**Important limitation.** Devices are normally discovered by polling Cisco ISE. AWS cannot reach your lab ISE, so with `ISE_BASE_URL` blank:
- the ISE banner shows and System Health says DEGRADED (expected, not a bug);
- no devices appear automatically.

You get devices two other ways, and this guide uses both:
1. **Real target.** Run `posture_agent.ps1 -ComputerName <target ip>` once from the backend box. The report creates the endpoint (ingest upserts by MAC). After that you can enqueue every check from the UI.
2. **Simulator.** The `sim` profile fakes ISE sessions and agent runs, so you get a big fleet in the dashboard.

For the resume, say: "Deployed on AWS (EC2 Windows, EC2 Linux, RDS, VPC, IAM, S3, CloudWatch). ISE integration was demonstrated in a lab; on AWS it ran against a real Windows target plus a simulated 2,000-device fleet."

---

## Architecture

```
Browser ──► Linux EC2 (Docker, Next.js :3000) ──/api/*──► Windows EC2 (Spring Boot :8090)
                                                              │          │
                                                              ▼          ▼
                                                       RDS Postgres   Windows target EC2
                                                       (private)      (WinRM/CIM checks)
```

Everything lives in one VPC, one region. Use **ap-south-1 (Mumbai)**.

---

## Stage 1: Account safety and IAM (1 hour)

1. **Root account:** sign in, open Security credentials, turn on MFA (authenticator app).
2. **Create an admin user (IAM):** IAM → Users → Create user `admin-you`.
   - Tick "Provide user access to the AWS Management Console".
   - Permissions: attach `AdministratorAccess` directly.
   - Turn on MFA for this user too. From now on sign in as this user, never as root.
3. **Budget:** Billing → Budgets → Create budget → Cost budget, $150/month, alerts at 50% and 80% to your email. Also open Billing preferences and enable free-tier / credit alerts.
4. **Region:** top right selector → Asia Pacific (Mumbai). Stay there.
5. **Key pair:** EC2 → Key Pairs → Create key pair `ep-key`, RSA, `.pem`. Save the file safely. You need it to get the Windows passwords and to SSH to Linux.

### IAM role for the servers (instance profile)

IAM → Roles → Create role → AWS service → EC2.
- Name: `ep-server-role`.
- Attach policies: `AmazonS3ReadOnlyAccess`, `AmazonSSMManagedInstanceCore`, `CloudWatchAgentServerPolicy`.

This lets the instances read your jar from S3, be managed by Systems Manager, and send logs, with **no access keys stored on disk**. You attach it when launching instances. (Least-privilege improvement later: replace S3ReadOnly with a policy for just your bucket.)

### AWS CLI on your PC (for uploads)

Install AWS CLI v2. IAM → Users → admin-you → Security credentials → Create access key (CLI). Then:

```powershell
aws configure     # paste key, secret, region ap-south-1, output json
aws sts get-caller-identity
```
Delete this access key when the project is finished.

---

## Stage 2: Network (1 to 2 hours)

Use the **default VPC** (fine for learning). Note its ID.

EC2 → Security Groups → create **four empty groups first** (so they can reference each other):

`lab-backend`, `lab-frontend`, `lab-db`, `lab-target`

Then add inbound rules:

| Group | Type | Port | Source |
|---|---|---|---|
| lab-backend | RDP | 3389 | My IP |
| lab-backend | Custom TCP | 8090 | lab-frontend |
| lab-frontend | SSH | 22 | My IP |
| lab-frontend | Custom TCP | 3000 | My IP |
| lab-db | PostgreSQL | 5432 | lab-backend |
| lab-db | PostgreSQL | 5432 | lab-frontend (admin access, see Stage 3) |
| lab-target | RDP | 3389 | My IP |
| lab-target | All TCP | 0-65535 | lab-backend (lab shortcut so WinRM/DCOM both work) |

"My IP" changes when your ISP changes it. If RDP or SSH stops working, update the rule.

**Never** open 5432, 8090 or 3389 to `0.0.0.0/0`.

---

## Stage 3: Database on RDS (1 hour)

1. RDS → Create database → Standard create → **PostgreSQL**, version **16.x**.
2. Template: **Free tier** if offered, otherwise Dev/Test. Single-AZ.
3. DB instance identifier `ep-db`. Master username `postgres`, auto-generate or set a password. **Save it in a password manager.**
4. Instance class: `db.t4g.micro`. Storage: 20 GB gp3, turn off storage autoscaling.
5. Connectivity: default VPC, **Public access: No**, VPC security group: `lab-db` (remove default).
6. Additional configuration → Initial database name: leave blank (we create it next). Backup retention 1 day.
7. Create. Wait about 10 minutes. Copy the **endpoint** (like `ep-db.xxxx.ap-south-1.rds.amazonaws.com`).

### Create the app database and user (using the Linux box)

You need a PostgreSQL client. Launch the Linux EC2 now (Stage 5, step 1) and then, over SSH:

```bash
sudo dnf install -y postgresql15
export PGPASSWORD='<master password>'
psql -h <rds-endpoint> -U postgres -d postgres
```

Inside psql:

```sql
CREATE USER ep_app WITH PASSWORD '<choose-db-password>';
GRANT ep_app TO postgres;                    -- RDS needs this before assigning ownership
CREATE DATABASE endpoint_posture OWNER ep_app;
\c endpoint_posture
CREATE EXTENSION IF NOT EXISTS pg_trgm;      -- migration V22 needs it
\q
```

Also create the simulator database now (used in Stage 9):

```sql
CREATE DATABASE endpoint_posture_sim OWNER ep_app;
\c endpoint_posture_sim
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

RDS PostgreSQL 15+ forces SSL by default, so the JDBC URL below includes `sslmode=require`.

---

## Stage 4: Package the backend on your PC (30 min)

```powershell
cd backend
mvn -DskipTests package
```
Result: `backend\target\endpoint-posture-java-0.1.0-SNAPSHOT.jar`. (Skip tests here, they need Docker. Your CI already runs them.)

Create an S3 bucket and upload:

```powershell
aws s3 mb s3://ep-artifacts-<your-name>-<random>
aws s3 cp backend\target\endpoint-posture-java-0.1.0-SNAPSHOT.jar s3://ep-artifacts-<...>/app.jar
aws s3 cp scripts s3://ep-artifacts-<...>/scripts --recursive --exclude "posture_common_cred.xml"
aws s3 cp scripts\sim\seed_50k.sql s3://ep-artifacts-<...>/seed_50k.sql
```
Never upload `application-dev.yml` or `posture_common_cred.xml`.

---

## Stage 5: Linux EC2 (frontend + admin box) (1 hour)

1. EC2 → Launch instance: name `ep-frontend`, **Amazon Linux 2023**, `t3.small`, key pair `ep-key`, security group `lab-frontend`, 20 GB gp3, Advanced → IAM instance profile `ep-server-role`.
2. Allocate and associate an **Elastic IP** (EC2 → Elastic IPs).
3. SSH in:
   ```bash
   ssh -i ep-key.pem ec2-user@<elastic-ip>
   ```
4. Install tools and add swap (Next.js builds need RAM):
   ```bash
   sudo dnf install -y docker git postgresql15
   sudo systemctl enable --now docker
   sudo usermod -aG docker ec2-user
   sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
   sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   exit        # log back in so the docker group applies
   ```
5. Do the Stage 3 psql step now.
6. **Build the frontend after the backend is running** (Stage 7), because `BACKEND_URL` is baked in at build time and you need the backend's private IP.

---

## Stage 6: Windows backend EC2 (half a day, the hard part)

### 6.1 Launch
EC2 → Launch instance: name `ep-backend`, **Microsoft Windows Server 2022 Base**, `t3.large`, key pair `ep-key`, `lab-backend`, 50 GB gp3, IAM instance profile `ep-server-role`. Allocate and attach an Elastic IP. Note its **private IP** too.

### 6.2 Connect (RDP)
Select instance → Connect → RDP client → Get password → upload `ep-key.pem` → copy the Administrator password. Open Remote Desktop on your PC, use the Elastic IP, user `Administrator`.

All following commands: PowerShell **as Administrator** on this server.

### 6.3 Install JDK 21 and make folders
```powershell
New-Item -ItemType Directory C:\ep\backend, C:\ep\scripts, C:\ep\tools -Force
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest "https://api.adoptium.net/v3/installer/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile C:\ep\tools\jdk21.msi
Start-Process msiexec.exe -Wait -ArgumentList '/i C:\ep\tools\jdk21.msi /qn ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJavaHome'
```
Open a **new** PowerShell window: `java -version` must say 21 and `$env:JAVA_HOME` should be set.

### 6.4 Download your files from S3
The Windows AMI includes AWS Tools for PowerShell. The instance role gives access.
```powershell
Read-S3Object -BucketName ep-artifacts-<...> -Key app.jar -File C:\ep\backend\app.jar
Read-S3Object -BucketName ep-artifacts-<...> -KeyPrefix scripts -Folder C:\ep\scripts
```
`backend` and `scripts` must stay siblings (application.yml uses `../scripts/...`).

### 6.5 Service account
```powershell
$pw = Read-Host -AsSecureString "Password for epsvc"
New-LocalUser epsvc -Password $pw -PasswordNeverExpires
Add-LocalGroupMember Administrators epsvc
Add-LocalGroupMember "Remote Desktop Users" epsvc
icacls C:\ep /grant "epsvc:(OI)(CI)F"
```
Admin rights are acceptable for a lab. Mention in your README that production should use a least-privilege account (your DECISIONS D7).

### 6.5b Generate secrets (on your PC or here)
```powershell
function R($n){ -join ((48..57)+(65..90)+(97..122) | Get-Random -Count $n | % {[char]$_}) }
R 48   # JWT_SECRET
R 32   # POSTURE_API_KEY
R 16   # SEED_ADMIN_PASSWORD (12+ chars)
```
Store all of them in a password manager.

### 6.6 Environment variables
```powershell
$v = @{
  DB_URL              = "jdbc:postgresql://<rds-endpoint>:5432/endpoint_posture?tcpKeepAlive=true&sslmode=require"
  DB_USERNAME         = "ep_app"
  DB_PASSWORD         = "<db password>"
  JWT_SECRET          = "<48 chars>"
  SEED_ADMIN_PASSWORD = "<16 chars>"
  POSTURE_API_KEY     = "<32 chars>"
}
$v.GetEnumerator() | % { [Environment]::SetEnvironmentVariable($_.Key, $_.Value, "Machine") }
```
Open a **new** PowerShell window afterwards. Check the network path:
```powershell
Test-NetConnection <rds-endpoint> -Port 5432     # TcpTestSucceeded : True
```
If False: check lab-db allows 5432 from lab-backend.

### 6.7 Save the credential for remote checks (as epsvc)
Sign out of RDP, reconnect as `epsvc`. In PowerShell:
```powershell
cd C:\ep\scripts
.\Save-PostureCredential.ps1
```
Enter `.\Administrator` and the **target** machine's Administrator password. (You create the target in Stage 8; you can re-run this script later to change it.) The file works only for `epsvc` on this machine.

### 6.8 First run by hand (as epsvc)
```powershell
cd C:\ep\backend
java -Xmx2g -Duser.timezone=UTC -jar app.jar
```
Look for: Flyway applying migrations up to V24, `>>> Seeded initial admin user: admin`, `Started EndpointPostureApplication`. Expect a warning about ISE not configured. In a second window:
```powershell
Invoke-RestMethod http://localhost:8090/actuator/health
```
Stop with Ctrl+C.

| Error | Cause |
|---|---|
| Placeholder `JWT_SECRET` not resolvable | env var missing, or you are in an old window |
| password authentication failed | wrong `DB_PASSWORD` or `ep_app` not created |
| Flyway fails at V22 | `pg_trgm` extension not created |
| WeakKeyException | `JWT_SECRET` under 32 bytes |
| Hangs on connect | security group or wrong endpoint |
| SSL error | missing `sslmode=require` |

### 6.9 Windows firewall
```powershell
New-NetFirewallRule -DisplayName "EP API" -Direction Inbound -Protocol TCP -LocalPort 8090 -Action Allow -RemoteAddress <frontend-private-ip>
```

### 6.10 Install as a service (WinSW), as Administrator
```powershell
Invoke-WebRequest "https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe" -OutFile C:\ep\backend\ep-service.exe
```
Create `C:\ep\backend\ep-service.xml`:
```xml
<service>
  <id>EndpointPostureEngine</id>
  <name>VE Endpoint Posture Engine</name>
  <executable>%JAVA_HOME%\bin\java.exe</executable>
  <arguments>-Xms512m -Xmx2g -Duser.timezone=UTC -jar C:\ep\backend\app.jar</arguments>
  <workingdirectory>C:\ep\backend</workingdirectory>
  <serviceaccount>
    <username>.\epsvc</username>
    <password>EPSVC_PASSWORD</password>
    <allowservicelogon>true</allowservicelogon>
  </serviceaccount>
  <onfailure action="restart" delay="10 sec"/>
  <log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>10</keepFiles></log>
</service>
```
```powershell
cd C:\ep\backend
.\ep-service.exe install
.\ep-service.exe start
Invoke-RestMethod http://localhost:8090/actuator/health
```
The XML holds a password in plain text: never commit it. Logs: `ep-service.out.log` and `ep-service.err.log`.

---

## Stage 7: Frontend (1 hour), on the Linux EC2

Get your code there (private repo: use a personal access token, or `scp -r frontend`):
```bash
git clone https://github.com/<you>/endpoint-posture-java.git
cd endpoint-posture-java/frontend
docker build --build-arg BACKEND_URL=http://<backend-private-ip>:8090 -t ep-frontend .
docker run -d --name ep-frontend --restart unless-stopped -p 3000:3000 ep-frontend
```
The published GHCR image will **not** work here because it was built for `host.docker.internal`.

Open `http://<frontend-elastic-ip>:3000`, log in as `admin` with your `SEED_ADMIN_PASSWORD`.

Check: Command Center loads, System Health page says DEGRADED because ISE is unreachable (expected).

---

## Stage 8: A real device to check (half a day)

1. Launch `ep-target`: Windows Server 2022, `t3.medium`, key pair `ep-key`, security group `lab-target`. Note its **private IP** and decrypted Administrator password.
2. RDP to it, PowerShell as Administrator:
   ```powershell
   Enable-PSRemoting -Force
   Set-ItemProperty HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System -Name LocalAccountTokenFilterPolicy -Value 1 -Type DWord
   Enable-NetFirewallRule -DisplayGroup "Windows Management Instrumentation (WMI)"
   Enable-NetFirewallRule -DisplayGroup "Windows Remote Management"
   ```
3. On the **backend** box (as Administrator), trust the target for WinRM by IP:
   ```powershell
   Set-Item WSMan:\localhost\Client\TrustedHosts -Value "<target-private-ip>" -Force
   ```
4. Re-run `Save-PostureCredential.ps1` as `epsvc` with `.\Administrator` and the target password.
5. As `epsvc` on the backend box, create the endpoint by running one check by hand:
   ```powershell
   $env:POSTURE_API_KEY = [Environment]::GetEnvironmentVariable("POSTURE_API_KEY","Machine")
   cd C:\ep\scripts
   .\posture_agent.ps1 -ComputerName <target-private-ip>
   ```
   Expect `Submitted OK`. (A first DCOM failure followed by a WSMan fallback is normal.)
6. In the dashboard open Endpoints. The target appears. On its page click **Check Hardware**, **Run Diagnostics**, **Run Security Scan** and watch the Jobs page.

Note: automatic rechecks only apply to devices flagged connected by ISE, so for this target you trigger checks manually. That is fine.

---

## Stage 9: Scale simulation (optional, 1 hour, great for a resume)

On the Linux box, seed the sim database:
```bash
aws s3 cp s3://ep-artifacts-<...>/seed_50k.sql .
PGPASSWORD='<ep_app password>' psql -h <rds-endpoint> -U ep_app -d endpoint_posture_sim -f seed_50k.sql
```
(Migrations V1 to V24 must exist in the sim database first, so do the next step once, then seed.)

On the backend box: stop the real service (`.\ep-service.exe stop`), then as `epsvc`:
```powershell
cd C:\ep\backend
java -Xmx2g -Duser.timezone=UTC -jar app.jar --spring.profiles.active=sim `
  "--spring.datasource.url=jdbc:postgresql://<rds-endpoint>:5432/endpoint_posture_sim?tcpKeepAlive=true&sslmode=require" `
  --app.sim.sessions=2000 --app.jobs.worker-threads=10 --spring.datasource.hikari.maximum-pool-size=15
```
Run the seed script after the first start (Flyway has created the tables), then restart it. Watch the `SIM` log lines (throughput, queue depth, latencies). Keep the numbers for your README. Use small values: `db.t4g.micro` has limited connections and memory. Stop it with Ctrl+C and start the real service again when done.

---

## Stage 10: Operations and polish (1 to 2 days)

- **CloudWatch alarms:** EC2 → each instance → Alarms: CPU above 80% for 10 minutes, status check failed, with email via SNS.
- **Secrets in SSM:** put the six secrets in Parameter Store (SecureString) and read them with the instance role instead of typing them. (Needs `ssm:GetParameter` on a policy added to `ep-server-role`.)
- **HTTPS:** get a free DuckDNS name pointing at the frontend Elastic IP, open 80 and 443 on `lab-frontend`, and run Caddy in Docker in front of port 3000. Close 3000. Then you may leave it up for demos.
- **Deploy automation:** GitHub Actions builds the jar, uploads it to S3, then Systems Manager Run Command stops the service, downloads the jar and restarts it.
- **Terraform (stretch goal):** recreate the VPC rules, RDS and EC2s as code. This is a strong resume line.
- **Backups:** RDS automated backup is already on (1 day). Take a manual snapshot before you tear things down if you want to keep the data.

---

## Verification checklist (screenshot each one)

- [ ] `actuator/health` is UP, backend runs as a Windows service and survives a reboot
- [ ] Login works from the browser on the Elastic IP
- [ ] Target device visible, posture, hardware, diagnostics, security scan results
- [ ] Share and Restrict do nothing harmful (ISE is blank, you get a clean failure, and it is still audited)
- [ ] Audit page shows the attempts
- [ ] Sim run numbers captured
- [ ] CloudWatch alarm and budget alert emails received
- [ ] Nothing exposed to `0.0.0.0/0` except HTTPS on the frontend

---

## Costs and teardown

Approximate in Mumbai if left on 24/7: Windows t3.large about $75, target t3.medium about $40, Linux t3.small about $15, RDS about $15 to $20, IPv4 addresses and disks about $15. Left on continuously that exceeds $150 per month, so:

- **Stop** (do not terminate) both Windows EC2s and the Linux EC2 when you stop for the day. Stop RDS too (it restarts itself after 7 days).
- Only run the target when you are testing.
- Check Billing every few days.

**Final teardown when done:** terminate EC2s, release Elastic IPs, delete RDS (take a final snapshot only if needed), delete snapshots, S3 bucket, unused security groups, and delete the CLI access key.

---

## What to write on your resume / README

- "Deployed a Spring Boot + Next.js endpoint-compliance platform on AWS: Windows EC2 backend running as a service, RDS PostgreSQL 16 in a private subnet, Dockerized frontend on Linux EC2, least-privilege IAM instance roles, security-group segmentation, S3 artifact delivery, CloudWatch alarms and budget alerts."
- Add the architecture diagram, screenshots, a cost table, the simulator results, and an honest "limitations" paragraph (ISE only in lab).
- Link the GitHub repo with a short DEPLOYMENT.md based on this guide (without any secrets).
