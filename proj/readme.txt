sqlserver-schema-extractor/
├── pom.xml
└── src/
    └── main/
        └── java/
            └── SqlServerSchemaExtractor.java
			

SQLSERVER_URL=jdbc:sqlserver://server:1433;databaseName=MyDatabase;encrypt=true;trustServerCertificate=true;
SQLSERVER_USER=myuser
SQLSERVER_PASSWORD=mypassword
DDL_OUTPUT=./output

java -cp target/sqlserver-schema-extractor-1.0.0.jar SqlServerSchemaExtractor

output/
├── 01_schemas.sql
├── 02_tables.sql
├── 03_primary_keys.sql
├── 04_unique_constraints.sql
├── 05_defaults.sql
├── 06_check_constraints.sql
├── 07_indexes.sql
├── 08_foreign_keys.sql
├── 09_views.sql
├── 10_functions.sql
├── 11_procedures.sql
├── 12_dependencies.sql
└── all.sql

SQL Server
    ↓
SQL Server Metadata
    ↓
Java object model
    ↓
 ┌───────────────────────┐
 │                       │
 ▼                       ▼
SQL Server DDL       PostgreSQL 17 DDL
    .sql                  .sql