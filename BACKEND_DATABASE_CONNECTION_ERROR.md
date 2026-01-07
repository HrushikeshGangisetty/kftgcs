# 🔴 Backend Database Connection Error - Troubleshooting Guide

## ❌ Error

```
TimeoutError: [Errno 10060] Connect call failed ('159.89.161.155', 24485)
ERROR: Application startup failed. Exiting.
```

**Problem:** Your FastAPI backend cannot connect to the PostgreSQL database.

---

## 🔍 Root Cause Analysis

The backend is trying to connect to:
- **Host:** 159.89.161.155
- **Port:** 24485

**Possible reasons for timeout:**
1. Database server is down or not running
2. Firewall blocking the connection
3. Database credentials are incorrect
4. Network connectivity issues
5. Database host/port configuration is wrong

---

## ✅ Solutions

### Solution 1: Check Database Server Status

**For Remote Database (DigitalOcean, AWS, etc.):**
1. Login to your database hosting provider
2. Verify the database instance is running
3. Check the connection details match your configuration
4. Verify firewall rules allow connections from your IP

**For Local Database:**
1. Check if PostgreSQL is running:
   ```bash
   # Windows
   services.msc
   # Look for "postgresql" service
   
   # Or check via command line
   pg_isready -h 159.89.161.155 -p 24485
   ```

---

### Solution 2: Verify Database Configuration

**Check your database configuration file:**

Location: `pavaman_gcs_app/db.py` or environment variables

**Expected configuration:**
```python
# db.py
DATABASE_URL = "postgresql://username:password@159.89.161.155:24485/database_name"

# Or using asyncpg.create_pool():
pool = await asyncpg.create_pool(
    host='159.89.161.155',
    port=24485,
    user='your_username',
    password='your_password',
    database='your_database_name',
    min_size=1,
    max_size=10,
)
```

**Verify:**
- ✅ Host IP is correct
- ✅ Port number is correct
- ✅ Username is correct
- ✅ Password is correct
- ✅ Database name exists

---

### Solution 3: Test Database Connection Manually

**Test connection using psql:**
```bash
psql -h 159.89.161.155 -p 24485 -U your_username -d your_database_name
```

**Test connection using Python:**
```python
import asyncpg
import asyncio

async def test_connection():
    try:
        conn = await asyncpg.connect(
            host='159.89.161.155',
            port=24485,
            user='your_username',
            password='your_password',
            database='your_database_name'
        )
        print("✅ Connection successful!")
        await conn.close()
    except Exception as e:
        print(f"❌ Connection failed: {e}")

asyncio.run(test_connection())
```

---

### Solution 4: Use Local PostgreSQL for Development

If you don't need the remote database, use a local PostgreSQL instance:

**1. Install PostgreSQL locally:**
- Download from: https://www.postgresql.org/download/windows/
- Install and set password for `postgres` user

**2. Create database:**
```sql
CREATE DATABASE pavaman_gcs;
```

**3. Update configuration:**
```python
# db.py
pool = await asyncpg.create_pool(
    host='localhost',    # ← Changed
    port=5432,           # ← Changed (default PostgreSQL port)
    user='postgres',
    password='your_password',
    database='pavaman_gcs',
    min_size=1,
    max_size=10,
)
```

**4. Run migrations:**
```bash
python manage.py migrate
```

---

### Solution 5: Check Firewall and Network

**Windows Firewall:**
1. Open Windows Defender Firewall
2. Click "Advanced settings"
3. Check Outbound Rules
4. Ensure connections to port 24485 are allowed

**Database Firewall (if remote):**
1. Login to your database provider
2. Check firewall/security rules
3. Add your IP address to allowed list
4. Ensure port 24485 is open

**Test network connectivity:**
```bash
# Test if port is reachable
telnet 159.89.161.155 24485

# Or use PowerShell
Test-NetConnection -ComputerName 159.89.161.155 -Port 24485
```

---

## 🔧 Temporary Workaround: Use SQLite

If you can't fix the PostgreSQL connection immediately, use SQLite for testing:

**1. Install dependencies:**
```bash
pip install databases[sqlite] aiosqlite
```

**2. Update configuration:**
```python
# db.py
from databases import Database

DATABASE_URL = "sqlite:///./pavaman_gcs.db"
database = Database(DATABASE_URL)

async def connect_db():
    await database.connect()
    print("✅ Connected to SQLite database")

async def disconnect_db():
    await database.disconnect()
```

**Note:** SQLite is only for development. Use PostgreSQL for production.

---

## 📝 Check Your Configuration File

**Find and open:** `pavaman_gcs_app/db.py`

Look for these values and verify they are correct:

```python
# What you should see:
host = '159.89.161.155'  # Is this the correct IP?
port = 24485             # Is this the correct port?
user = 'your_username'   # Is this correct?
password = 'your_password'  # Is this correct?
database = 'your_database_name'  # Does this database exist?
```

---

## ✅ Quick Fix Checklist

- [ ] Database server is running
- [ ] Database credentials are correct
- [ ] Firewall allows connections to port 24485
- [ ] Network connectivity to 159.89.161.155 works
- [ ] Database exists and user has permissions
- [ ] Configuration file has correct values

---

## 🚀 After Fixing Database Connection

Once your database is connected and backend starts successfully:

**Expected logs:**
```
INFO:     Uvicorn running on http://0.0.0.0:8080 (Press CTRL+C to quit)
INFO:     Started server process [XXXXX]
INFO:     Waiting for application startup.
✅ Connected to database  ← SUCCESS!
INFO:     Application startup complete.
```

**Then your Android app will work:**
```
Android connects → Backend creates mission → Telemetry flows
```

---

## 📞 Common Error Messages

### "Connection refused"
- Database server is not running
- Port is wrong
- Firewall blocking connection

### "Authentication failed"
- Username or password is incorrect
- User doesn't have permissions

### "Database does not exist"
- Database name is wrong
- Database hasn't been created yet

### "Timeout"
- Cannot reach database server (current error)
- Network issue or firewall blocking

---

## 🎯 Summary

**Current Issue:** Backend cannot connect to PostgreSQL database at `159.89.161.155:24485`

**Action Required:**
1. Verify database server is running
2. Check database credentials in configuration
3. Test connection manually
4. Fix firewall/network issues
5. Or use local PostgreSQL/SQLite for development

**Android App Status:** ✅ Ready and waiting for backend to start

Once backend starts successfully, your Android app with `pilot_id=7` will work perfectly!

---

**Need Help?** Share your `db.py` file or database configuration, and I can help you fix the connection.

