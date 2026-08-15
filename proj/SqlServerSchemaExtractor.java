import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class SqlServerSchemaExtractor {

    /*
     * ============================================================
     * Configuration
     * ============================================================
     */

    private static final String JDBC_URL =
            System.getenv().getOrDefault(
                    "SQLSERVER_URL",
                    "jdbc:sqlserver://localhost:1433;" +
                    "databaseName=MyDatabase;" +
                    "encrypt=true;" +
                    "trustServerCertificate=true;"
            );

    private static final String USER =
            System.getenv().getOrDefault("SQLSERVER_USER", "sa");

    private static final String PASSWORD =
            System.getenv().getOrDefault("SQLSERVER_PASSWORD", "password");

    private static final Path OUTPUT =
            Paths.get(
                    System.getenv().getOrDefault(
                            "DDL_OUTPUT",
                            "output"
                    )
            );

    /*
     * ============================================================
     * Main
     * ============================================================
     */

    public static void main(String[] args) {

        System.out.println("SQL Server DDL Extractor");
        System.out.println("========================");
        System.out.println();

        try (Connection connection =
                     DriverManager.getConnection(
                             JDBC_URL,
                             USER,
                             PASSWORD)) {

            System.out.println(
                    "Connected to database: "
                            + connection.getCatalog()
            );

            Files.createDirectories(OUTPUT);

            DatabaseExtractor extractor =
                    new DatabaseExtractor(connection);

            extractor.extract(OUTPUT);

            System.out.println();
            System.out.println("DDL extraction completed.");
            System.out.println("Output: " +
                    OUTPUT.toAbsolutePath());

        } catch (Exception e) {

            System.err.println(
                    "DDL extraction failed:"
            );

            e.printStackTrace();
            System.exit(1);
        }
    }

    /*
     * ============================================================
     * Database Extractor
     * ============================================================
     */

    static class DatabaseExtractor {

        private final Connection connection;

        DatabaseExtractor(Connection connection) {
            this.connection = connection;
        }

        void extract(Path output) throws Exception {

            cleanOutputDirectory(output);

            List<String> schemas =
                    getSchemas();

            List<Table> tables =
                    getTables();

            List<PrimaryKey> primaryKeys =
                    getPrimaryKeys();

            List<UniqueConstraint> uniqueConstraints =
                    getUniqueConstraints();

            List<CheckConstraint> checkConstraints =
                    getCheckConstraints();

            List<ForeignKey> foreignKeys =
                    getForeignKeys();

            List<IndexInfo> indexes =
                    getIndexes();

            List<ViewInfo> views =
                    getViews();

            List<ProgrammableObject> procedures =
                    getProgrammableObjects(
                            "P"
                    );

            List<ProgrammableObject> functions =
                    getProgrammableObjects(
                            "FN",
                            "IF",
                            "TF"
                    );

            List<Dependency> dependencies =
                    getDependencies();

            /*
             * Generate files
             */

            writeSchemas(
                    output.resolve("01_schemas.sql"),
                    schemas
            );

            writeTables(
                    output.resolve("02_tables.sql"),
                    tables
            );

            writePrimaryKeys(
                    output.resolve("03_primary_keys.sql"),
                    primaryKeys
            );

            writeUniqueConstraints(
                    output.resolve("04_unique_constraints.sql"),
                    uniqueConstraints
            );

            writeDefaults(
                    output.resolve("05_defaults.sql"),
                    tables
            );

            writeChecks(
                    output.resolve("06_check_constraints.sql"),
                    checkConstraints
            );

            writeIndexes(
                    output.resolve("07_indexes.sql"),
                    indexes
            );

            writeForeignKeys(
                    output.resolve("08_foreign_keys.sql"),
                    foreignKeys
            );

            writeViews(
                    output.resolve("09_views.sql"),
                    views
            );

            writeFunctions(
                    output.resolve("10_functions.sql"),
                    functions
            );

            writeProcedures(
                    output.resolve("11_procedures.sql"),
                    procedures
            );

            writeDependencies(
                    output.resolve("12_dependencies.sql"),
                    dependencies
            );

            /*
             * Combined script
             */

            writeAllScript(output);

            /*
             * Summary
             */

            System.out.println();
            System.out.println("Schemas       : " + schemas.size());
            System.out.println("Tables        : " + tables.size());
            System.out.println("Primary Keys  : " + primaryKeys.size());
            System.out.println("Unique Keys   : " + uniqueConstraints.size());
            System.out.println("Checks        : " + checkConstraints.size());
            System.out.println("Indexes       : " + indexes.size());
            System.out.println("Foreign Keys  : " + foreignKeys.size());
            System.out.println("Views         : " + views.size());
            System.out.println("Functions     : " + functions.size());
            System.out.println("Procedures    : " + procedures.size());
            System.out.println("Dependencies  : " + dependencies.size());
        }

        /*
         * ========================================================
         * Schemas
         * ========================================================
         */

        List<String> getSchemas() throws SQLException {

            String sql = """
                    SELECT name
                    FROM sys.schemas
                    WHERE name NOT IN
                    (
                        'guest',
                        'INFORMATION_SCHEMA',
                        'sys'
                    )
                    ORDER BY name
                    """;

            List<String> result =
                    new ArrayList<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {
                    result.add(
                            rs.getString("name")
                    );
                }
            }

            return result;
        }

        /*
         * ========================================================
         * Tables / Columns
         * ========================================================
         */

        List<Table> getTables() throws SQLException {

            String sql = """
                    SELECT
                        s.name AS schema_name,
                        t.name AS table_name,
                        c.column_id,
                        c.name AS column_name,
                        ty.name AS data_type,
                        c.max_length,
                        c.precision,
                        c.scale,
                        c.is_nullable,
                        c.is_identity,
                        ic.seed_value,
                        ic.increment_value,
                        dc.definition AS default_definition
                    FROM sys.tables t
                    JOIN sys.schemas s
                        ON t.schema_id = s.schema_id
                    JOIN sys.columns c
                        ON t.object_id = c.object_id
                    JOIN sys.types ty
                        ON c.user_type_id = ty.user_type_id
                    LEFT JOIN sys.identity_columns ic
                        ON c.object_id = ic.object_id
                       AND c.column_id = ic.column_id
                    LEFT JOIN sys.default_constraints dc
                        ON c.default_object_id = dc.object_id
                    WHERE t.is_ms_shipped = 0
                    ORDER BY
                        s.name,
                        t.name,
                        c.column_id
                    """;

            Map<String, Table> tableMap =
                    new LinkedHashMap<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    String schema =
                            rs.getString("schema_name");

                    String tableName =
                            rs.getString("table_name");

                    String key =
                            schema + "." + tableName;

                    Table table =
                            tableMap.computeIfAbsent(
                                    key,
                                    k -> new Table(
                                            schema,
                                            tableName
                                    )
                            );

                    Column column =
                            new Column();

                    column.name =
                            rs.getString("column_name");

                    column.dataType =
                            rs.getString("data_type");

                    column.maxLength =
                            rs.getInt("max_length");

                    column.precision =
                            rs.getInt("precision");

                    column.scale =
                            rs.getInt("scale");

                    column.nullable =
                            rs.getBoolean("is_nullable");

                    column.identity =
                            rs.getBoolean("is_identity");

                    column.seed =
                            getLongObject(
                                    rs,
                                    "seed_value"
                            );

                    column.increment =
                            getLongObject(
                                    rs,
                                    "increment_value"
                            );

                    column.defaultDefinition =
                            rs.getString(
                                    "default_definition"
                            );

                    table.columns.add(column);
                }
            }

            return new ArrayList<>(
                    tableMap.values()
            );
        }

        /*
         * ========================================================
         * Primary Keys
         * ========================================================
         */

        List<PrimaryKey> getPrimaryKeys()
                throws SQLException {

            String sql = """
                    SELECT
                        s.name AS schema_name,
                        t.name AS table_name,
                        kc.name AS constraint_name,
                        c.name AS column_name,
                        ic.key_ordinal
                    FROM sys.key_constraints kc
                    JOIN sys.tables t
                        ON kc.parent_object_id =
                           t.object_id
                    JOIN sys.schemas s
                        ON t.schema_id =
                           s.schema_id
                    JOIN sys.index_columns ic
                        ON kc.parent_object_id =
                           ic.object_id
                       AND kc.unique_index_id =
                           ic.index_id
                    JOIN sys.columns c
                        ON ic.object_id =
                           c.object_id
                       AND ic.column_id =
                           c.column_id
                    WHERE kc.type = 'PK'
                    ORDER BY
                        s.name,
                        t.name,
                        kc.name,
                        ic.key_ordinal
                    """;

            Map<String, PrimaryKey> keys =
                    new LinkedHashMap<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    String schema =
                            rs.getString("schema_name");

                    String table =
                            rs.getString("table_name");

                    String name =
                            rs.getString(
                                    "constraint_name"
                            );

                    String key =
                            schema + "." +
                            table + "." +
                            name;

                    PrimaryKey pk =
                            keys.computeIfAbsent(
                                    key,
                                    k -> {
                                        PrimaryKey p =
                                                new PrimaryKey();
                                        p.schema = schema;
                                        p.table = table;
                                        p.name = name;
                                        return p;
                                    }
                            );

                    pk.columns.add(
                            rs.getString(
                                    "column_name"
                            )
                    );
                }
            }

            return new ArrayList<>(
                    keys.values()
            );
        }

        /*
         * ========================================================
         * Unique Constraints
         * ========================================================
         */

        List<UniqueConstraint> getUniqueConstraints()
                throws SQLException {

            String sql = """
                    SELECT
                        s.name AS schema_name,
                        t.name AS table_name,
                        kc.name AS constraint_name,
                        c.name AS column_name,
                        ic.key_ordinal
                    FROM sys.key_constraints kc
                    JOIN sys.tables t
                        ON kc.parent_object_id =
                           t.object_id
                    JOIN sys.schemas s
                        ON t.schema_id =
                           s.schema_id
                    JOIN sys.index_columns ic
                        ON kc.parent_object_id =
                           ic.object_id
                       AND kc.unique_index_id =
                           ic.index_id
                    JOIN sys.columns c
                        ON ic.object_id =
                           c.object_id
                       AND ic.column_id =
                           c.column_id
                    WHERE kc.type = 'UQ'
                    ORDER BY
                        s.name,
                        t.name,
                        kc.name,
                        ic.key_ordinal
                    """;

            Map<String, UniqueConstraint> map =
                    new LinkedHashMap<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    String schema =
                            rs.getString("schema_name");

                    String table =
                            rs.getString("table_name");

                    String name =
                            rs.getString(
                                    "constraint_name"
                            );

                    String key =
                            schema + "." +
                            table + "." +
                            name;

                    UniqueConstraint uq =
                            map.computeIfAbsent(
                                    key,
                                    k -> {
                                        UniqueConstraint x =
                                                new UniqueConstraint();
                                        x.schema = schema;
                                        x.table = table;
                                        x.name = name;
                                        return x;
                                    }
                            );

                    uq.columns.add(
                            rs.getString(
                                    "column_name"
                            )
                    );
                }
            }

            return new ArrayList<>(
                    map.values()
            );
        }

        /*
         * ========================================================
         * Check Constraints
         * ========================================================
         */

        List<CheckConstraint> getCheckConstraints()
                throws SQLException {

            String sql = """
                    SELECT
                        s.name AS schema_name,
                        t.name AS table_name,
                        cc.name AS constraint_name,
                        cc.definition
                    FROM sys.check_constraints cc
                    JOIN sys.tables t
                        ON cc.parent_object_id =
                           t.object_id
                    JOIN sys.schemas s
                        ON t.schema_id =
                           s.schema_id
                    WHERE t.is_ms_shipped = 0
                    ORDER BY
                        s.name,
                        t.name,
                        cc.name
                    """;

            List<CheckConstraint> result =
                    new ArrayList<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    CheckConstraint cc =
                            new CheckConstraint();

                    cc.schema =
                            rs.getString("schema_name");

                    cc.table =
                            rs.getString("table_name");

                    cc.name =
                            rs.getString(
                                    "constraint_name"
                            );

                    cc.definition =
                            rs.getString("definition");

                    result.add(cc);
                }
            }

            return result;
        }

        /*
         * ========================================================
         * Foreign Keys
         * ========================================================
         */

        List<ForeignKey> getForeignKeys()
                throws SQLException {

            String sql = """
                    SELECT
                        sch.name AS schema_name,
                        tab.name AS table_name,
                        fk.name AS foreign_key_name,
                        col.name AS column_name,
                        ref_sch.name AS referenced_schema,
                        ref_tab.name AS referenced_table,
                        ref_col.name AS referenced_column,
                        fkc.constraint_column_id
                    FROM sys.foreign_key_columns fkc
                    JOIN sys.foreign_keys fk
                        ON fkc.constraint_object_id =
                           fk.object_id
                    JOIN sys.tables tab
                        ON fkc.parent_object_id =
                           tab.object_id
                    JOIN sys.schemas sch
                        ON tab.schema_id =
                           sch.schema_id
                    JOIN sys.columns col
                        ON fkc.parent_object_id =
                           col.object_id
                       AND fkc.parent_column_id =
                           col.column_id
                    JOIN sys.tables ref_tab
                        ON fkc.referenced_object_id =
                           ref_tab.object_id
                    JOIN sys.schemas ref_sch
                        ON ref_tab.schema_id =
                           ref_sch.schema_id
                    JOIN sys.columns ref_col
                        ON fkc.referenced_object_id =
                           ref_col.object_id
                       AND fkc.referenced_column_id =
                           ref_col.column_id
                    ORDER BY
                        sch.name,
                        tab.name,
                        fk.name,
                        fkc.constraint_column_id
                    """;

            Map<String, ForeignKey> map =
                    new LinkedHashMap<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    String schema =
                            rs.getString("schema_name");

                    String table =
                            rs.getString("table_name");

                    String name =
                            rs.getString(
                                    "foreign_key_name"
                            );

                    String key =
                            schema + "." +
                            table + "." +
                            name;

                    ForeignKey fk =
                            map.computeIfAbsent(
                                    key,
                                    k -> {
                                        ForeignKey f =
                                                new ForeignKey();

                                        f.schema = schema;
                                        f.table = table;
                                        f.name = name;

                                        f.referencedSchema =
                                                rsString(
                                                    rs,
                                                    "referenced_schema"
                                                );

                                        f.referencedTable =
                                                rsString(
                                                    rs,
                                                    "referenced_table"
                                                );

                                        return f;
                                    }
                            );

                    fk.columns.add(
                            new ColumnMapping(
                                    rs.getString(
                                            "column_name"
                                    ),
                                    rs.getString(
                                            "referenced_column"
                                    )
                            )
                    );
                }
            }

            return new ArrayList<>(
                    map.values()
            );
        }

        /*
         * ========================================================
         * Indexes
         * ========================================================
         */

        List<IndexInfo> getIndexes()
                throws SQLException {

            String sql = """
                    SELECT
                        s.name AS schema_name,
                        t.name AS table_name,
                        i.name AS index_name,
                        i.type_desc,
                        i.is_unique,
                        i.is_primary_key,
                        i.is_unique_constraint,
                        c.name AS column_name,
                        ic.key_ordinal,
                        ic.is_included_column
                    FROM sys.indexes i
                    JOIN sys.tables t
                        ON i.object_id =
                           t.object_id
                    JOIN sys.schemas s
                        ON t.schema_id =
                           s.schema_id
                    JOIN sys.index_columns ic
                        ON i.object_id =
                           ic.object_id
                       AND i.index_id =
                           ic.index_id
                    JOIN sys.columns c
                        ON ic.object_id =
                           c.object_id
                       AND ic.column_id =
                           c.column_id
                    WHERE
                        t.is_ms_shipped = 0
                        AND i.name IS NOT NULL
                        AND i.is_primary_key = 0
                        AND i.is_unique_constraint = 0
                    ORDER BY
                        s.name,
                        t.name,
                        i.name,
                        ic.key_ordinal
                    """;

            Map<String, IndexInfo> map =
                    new LinkedHashMap<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    String schema =
                            rs.getString("schema_name");

                    String table =
                            rs.getString("table_name");

                    String name =
                            rs.getString("index_name");

                    String key =
                            schema + "." +
                            table + "." +
                            name;

                    IndexInfo index =
                            map.computeIfAbsent(
                                    key,
                                    k -> {
                                        IndexInfo i =
                                                new IndexInfo();

                                        i.schema = schema;
                                        i.table = table;
                                        i.name = name;
                                        i.unique =
                                            rsBool(
                                                rs,
                                                "is_unique"
                                            );
                                        i.type =
                                            rsString(
                                                rs,
                                                "type_desc"
                                            );

                                        return i;
                                    }
                            );

                    index.columns.add(
                            rs.getString(
                                    "column_name"
                            )
                    );
                }
            }

            return new ArrayList<>(
                    map.values()
            );
        }

        /*
         * ========================================================
         * Views
         * ========================================================
         */

        List<ViewInfo> getViews()
                throws SQLException {

            String sql = """
                    SELECT
                        s.name AS schema_name,
                        v.name AS view_name,
                        m.definition
                    FROM sys.views v
                    JOIN sys.schemas s
                        ON v.schema_id =
                           s.schema_id
                    LEFT JOIN sys.sql_modules m
                        ON v.object_id =
                           m.object_id
                    WHERE v.is_ms_shipped = 0
                    ORDER BY
                        s.name,
                        v.name
                    """;

            List<ViewInfo> result =
                    new ArrayList<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    ViewInfo view =
                            new ViewInfo();

                    view.schema =
                            rs.getString("schema_name");

                    view.name =
                            rs.getString("view_name");

                    view.definition =
                            rs.getString("definition");

                    result.add(view);
                }
            }

            return result;
        }

        /*
         * ========================================================
         * Procedures / Functions
         * ========================================================
         */

        List<ProgrammableObject>
        getProgrammableObjects(
                String... types
        ) throws SQLException {

            String placeholders =
                    String.join(
                            ",",
                            Collections.nCopies(
                                    types.length,
                                    "?"
                            )
                    );

            String sql = """
                    SELECT
                        s.name AS schema_name,
                        o.name AS object_name,
                        o.type,
                        o.type_desc,
                        m.definition
                    FROM sys.objects o
                    JOIN sys.schemas s
                        ON o.schema_id =
                           s.schema_id
                    JOIN sys.sql_modules m
                        ON o.object_id =
                           m.object_id
                    WHERE o.type IN (%s)
                    ORDER BY
                        s.name,
                        o.name
                    """.formatted(placeholders);

            List<ProgrammableObject> result =
                    new ArrayList<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql)) {

                for (int i = 0;
                     i < types.length;
                     i++) {

                    ps.setString(i + 1, types[i]);
                }

                try (ResultSet rs =
                             ps.executeQuery()) {

                    while (rs.next()) {

                        ProgrammableObject object =
                                new ProgrammableObject();

                        object.schema =
                                rs.getString(
                                        "schema_name"
                                );

                        object.name =
                                rs.getString(
                                        "object_name"
                                );

                        object.type =
                                rs.getString("type_desc");

                        object.definition =
                                rs.getString(
                                        "definition"
                                );

                        result.add(object);
                    }
                }
            }

            return result;
        }

        /*
         * ========================================================
         * Dependencies
         * ========================================================
         */

        List<Dependency> getDependencies()
                throws SQLException {

            String sql = """
                    SELECT
                        OBJECT_SCHEMA_NAME(
                            d.referencing_id
                        ) AS referencing_schema,

                        OBJECT_NAME(
                            d.referencing_id
                        ) AS referencing_object,

                        d.referenced_schema_name,
                        d.referenced_entity_name,
                        d.referenced_minor_name,

                        d.is_schema_bound_reference
                    FROM
                        sys.sql_expression_dependencies d
                    WHERE
                        d.referencing_id IS NOT NULL
                    ORDER BY
                        referencing_schema,
                        referencing_object
                    """;

            List<Dependency> result =
                    new ArrayList<>();

            try (PreparedStatement ps =
                         connection.prepareStatement(sql);
                 ResultSet rs =
                         ps.executeQuery()) {

                while (rs.next()) {

                    Dependency d =
                            new Dependency();

                    d.referencingSchema =
                            rs.getString(
                                    "referencing_schema"
                            );

                    d.referencingObject =
                            rs.getString(
                                    "referencing_object"
                            );

                    d.referencedSchema =
                            rs.getString(
                                    "referenced_schema_name"
                            );

                    d.referencedObject =
                            rs.getString(
                                    "referenced_entity_name"
                            );

                    d.referencedColumn =
                            rs.getString(
                                    "referenced_minor_name"
                            );

                    result.add(d);
                }
            }

            return result;
        }

        /*
         * ========================================================
         * File generation
         * ========================================================
         */

        void writeSchemas(
                Path file,
                List<String> schemas
        ) throws IOException {

            StringBuilder sql =
                    header("SCHEMAS");

            for (String schema : schemas) {

                sql.append(
                        "IF NOT EXISTS (" +
                        "SELECT 1 FROM sys.schemas " +
                        "WHERE name = N'"
                );

                sql.append(
                        escapeSqlString(schema)
                );

                sql.append(
                        "')\n"
                );

                sql.append(
                        "    EXEC('CREATE SCHEMA "
                );

                sql.append(
                        quote(schema)
                );

                sql.append(
                        "');\n\n"
                );
            }

            write(file, sql);
        }

        void writeTables(
                Path file,
                List<Table> tables
        ) throws IOException {

            StringBuilder sql =
                    header("TABLES");

            for (Table table : tables) {

                sql.append(
                        "CREATE TABLE "
                );

                sql.append(
                        qualifiedName(
                                table.schema,
                                table.name
                        )
                );

                sql.append(" (\n");

                for (int i = 0;
                     i < table.columns.size();
                     i++) {

                    Column c =
                            table.columns.get(i);

                    sql.append("    ");
                    sql.append(quote(c.name));
                    sql.append(" ");
                    sql.append(
                            sqlServerType(c)
                    );

                    if (c.identity) {

                        sql.append(
                                " IDENTITY("
                        );

                        sql.append(
                                c.seed == null
                                    ? "1"
                                    : c.seed
                        );

                        sql.append(",");

                        sql.append(
                                c.increment == null
                                    ? "1"
                                    : c.increment
                        );

                        sql.append(")");
                    }

                    if (!c.nullable) {
                        sql.append(
                                " NOT NULL"
                        );
                    } else {
                        sql.append(
                                " NULL"
                        );
                    }

                    if (i <
                            table.columns.size() - 1) {

                        sql.append(",");
                    }

                    sql.append("\n");
                }

                sql.append(");\n\n");
            }

            write(file, sql);
        }

        void writePrimaryKeys(
                Path file,
                List<PrimaryKey> keys
        ) throws IOException {

            StringBuilder sql =
                    header("PRIMARY KEYS");

            for (PrimaryKey pk : keys) {

                sql.append(
                        "ALTER TABLE "
                );

                sql.append(
                        qualifiedName(
                                pk.schema,
                                pk.table
                        )
                );

                sql.append(
                        " ADD CONSTRAINT "
                );

                sql.append(
                        quote(pk.name)
                );

                sql.append(
                        " PRIMARY KEY ("
                );

                appendColumns(
                        sql,
                        pk.columns
                );

                sql.append(");\n\n");
            }

            write(file, sql);
        }

        void writeUniqueConstraints(
                Path file,
                List<UniqueConstraint> constraints
        ) throws IOException {

            StringBuilder sql =
                    header("UNIQUE CONSTRAINTS");

            for (UniqueConstraint uq :
                    constraints) {

                sql.append(
                        "ALTER TABLE "
                );

                sql.append(
                        qualifiedName(
                                uq.schema,
                                uq.table
                        )
                );

                sql.append(
                        " ADD CONSTRAINT "
                );

                sql.append(
                        quote(uq.name)
                );

                sql.append(
                        " UNIQUE ("
                );

                appendColumns(
                        sql,
                        uq.columns
                );

                sql.append(");\n\n");
            }

            write(file, sql);
        }

        void writeDefaults(
                Path file,
                List<Table> tables
        ) throws IOException {

            StringBuilder sql =
                    header("DEFAULT CONSTRAINTS");

            for (Table table : tables) {

                for (Column c :
                        table.columns) {

                    if (c.defaultDefinition ==
                            null) {
                        continue;
                    }

                    sql.append(
                            "ALTER TABLE "
                    );

                    sql.append(
                            qualifiedName(
                                    table.schema,
                                    table.name
                            )
                    );

                    sql.append(
                            " ADD DEFAULT "
                    );

                    sql.append(
                            c.defaultDefinition
                    );

                    sql.append(
                            " FOR "
                    );

                    sql.append(
                            quote(c.name)
                    );

                    sql.append(";\n\n");
                }
            }

            write(file, sql);
        }

        void writeChecks(
                Path file,
                List<CheckConstraint> checks
        ) throws IOException {

            StringBuilder sql =
                    header("CHECK CONSTRAINTS");

            for (CheckConstraint cc :
                    checks) {

                sql.append(
                        "ALTER TABLE "
                );

                sql.append(
                        qualifiedName(
                                cc.schema,
                                cc.table
                        )
                );

                sql.append(
                        " ADD CONSTRAINT "
                );

                sql.append(
                        quote(cc.name)
                );

                sql.append(
                        " CHECK "
                );

                sql.append(
                        cc.definition
                );

                sql.append(";\n\n");
            }

            write(file, sql);
        }

        void writeIndexes(
                Path file,
                List<IndexInfo> indexes
        ) throws IOException {

            StringBuilder sql =
                    header("INDEXES");

            for (IndexInfo index :
                    indexes) {

                sql.append(
                        "CREATE "
                );

                if (index.unique) {
                    sql.append("UNIQUE ");
                }

                sql.append(
                        "INDEX "
                );

                sql.append(
                        quote(index.name)
                );

                sql.append(
                        " ON "
                );

                sql.append(
                        qualifiedName(
                                index.schema,
                                index.table
                        )
                );

                sql.append(" (");

                appendColumns(
                        sql,
                        index.columns
                );

                sql.append(");\n\n");
            }

            write(file, sql);
        }

        void writeForeignKeys(
                Path file,
                List<ForeignKey> keys
        ) throws IOException {

            StringBuilder sql =
                    header("FOREIGN KEYS");

            for (ForeignKey fk : keys) {

                sql.append(
                        "ALTER TABLE "
                );

                sql.append(
                        qualifiedName(
                                fk.schema,
                                fk.table
                        )
                );

                sql.append(
                        " ADD CONSTRAINT "
                );

                sql.append(
                        quote(fk.name)
                );

                sql.append(
                        " FOREIGN KEY ("
                );

                List<String> local =
                        fk.columns.stream()
                                .map(x ->
                                    x.localColumn)
                                .toList();

                List<String> remote =
                        fk.columns.stream()
                                .map(x ->
                                    x.referencedColumn)
                                .toList();

                appendColumns(
                        sql,
                        local
                );

                sql.append(
                        ") REFERENCES "
                );

                sql.append(
                        qualifiedName(
                                fk.referencedSchema,
                                fk.referencedTable
                        )
                );

                sql.append(" (");

                appendColumns(
                        sql,
                        remote
                );

                sql.append(");\n\n");
            }

            write(file, sql);
        }

        void writeViews(
                Path file,
                List<ViewInfo> views
        ) throws IOException {

            StringBuilder sql =
                    header("VIEWS");

            for (ViewInfo view : views) {

                if (view.definition == null) {
                    continue;
                }

                sql.append(
                        "CREATE VIEW "
                );

                sql.append(
                        qualifiedName(
                                view.schema,
                                view.name
                        )
                );

                sql.append(" AS\n");

                sql.append(
                        view.definition.trim()
                );

                sql.append("\nGO\n\n");
            }

            write(file, sql);
        }

        void writeFunctions(
                Path file,
                List<ProgrammableObject> objects
        ) throws IOException {

            StringBuilder sql =
                    header("FUNCTIONS");

            for (ProgrammableObject object :
                    objects) {

                sql.append(
                        object.definition
                );

                sql.append("\nGO\n\n");
            }

            write(file, sql);
        }

        void writeProcedures(
                Path file,
                List<ProgrammableObject> objects
        ) throws IOException {

            StringBuilder sql =
                    header("STORED PROCEDURES");

            for (ProgrammableObject object :
                    objects) {

                sql.append(
                        object.definition
                );

                sql.append("\nGO\n\n");
            }

            write(file, sql);
        }

        void writeDependencies(
                Path file,
                List<Dependency> dependencies
        ) throws IOException {

            StringBuilder sql =
                    header("OBJECT DEPENDENCIES");

            sql.append(
                    "-- This file is informational.\n"
            );

            sql.append(
                    "-- It records SQL Server object dependencies.\n\n"
            );

            for (Dependency d :
                    dependencies) {

                sql.append("-- ");

                sql.append(
                        d.referencingSchema
                );

                sql.append(".");

                sql.append(
                        d.referencingObject
                );

                sql.append(" -> ");

                if (d.referencedSchema != null) {

                    sql.append(
                            d.referencedSchema
                    );

                    sql.append(".");
                }

                sql.append(
                        d.referencedObject
                );

                if (d.referencedColumn != null) {

                    sql.append(".");

                    sql.append(
                            d.referencedColumn
                    );
                }

                sql.append("\n");
            }

            write(file, sql);
        }

        /*
         * ========================================================
         * all.sql
         * ========================================================
         */

        void writeAllScript(Path output)
                throws IOException {

            StringBuilder sql =
                    header("COMPLETE DATABASE DDL");

            String[] files = {
                    "01_schemas.sql",
                    "02_tables.sql",
                    "03_primary_keys.sql",
                    "04_unique_constraints.sql",
                    "05_defaults.sql",
                    "06_check_constraints.sql",
                    "07_indexes.sql",
                    "08_foreign_keys.sql",
                    "09_views.sql",
                    "10_functions.sql",
                    "11_procedures.sql"
            };

            for (String file : files) {

                sql.append(
                        "-- =====================================================\n"
                );

                sql.append(
                        "-- "
                );

                sql.append(file);

                sql.append(
                        "\n-- =====================================================\n\n"
                );

                Path path =
                        output.resolve(file);

                if (Files.exists(path)) {

                    sql.append(
                            Files.readString(
                                    path,
                                    StandardCharsets.UTF_8
                            )
                    );

                    sql.append("\n");
                }
            }

            write(
                    output.resolve("all.sql"),
                    sql
            );
        }

        /*
         * ========================================================
         * SQL type generation
         * ========================================================
         */

        String sqlServerType(Column c) {

            String type =
                    c.dataType.toLowerCase(
                            Locale.ROOT
                    );

            return switch (type) {

                case "varchar",
                     "char",
                     "varbinary",
                     "binary" -> {

                    if (c.maxLength == -1) {
                        yield type + "(MAX)";
                    }

                    yield type +
                            "(" +
                            c.maxLength +
                            ")";
                }

                case "nvarchar",
                     "nchar" -> {

                    if (c.maxLength == -1) {
                        yield type + "(MAX)";
                    }

                    yield type +
                            "(" +
                            (c.maxLength / 2) +
                            ")";
                }

                case "decimal",
                     "numeric" ->

                    type +
                    "(" +
                    c.precision +
                    "," +
                    c.scale +
                    ")";

                case "datetime2" ->

                    "datetime2(" +
                    c.scale +
                    ")";

                case "time" ->

                    "time(" +
                    c.scale +
                    ")";

                default -> type;
            };
        }

        /*
         * ========================================================
         * Helpers
         * ========================================================
         */

        String quote(String name) {

            return "[" +
                    name.replace("]", "]]") +
                    "]";
        }

        String qualifiedName(
                String schema,
                String object
        ) {

            return quote(schema) +
                    "." +
                    quote(object);
        }

        void appendColumns(
                StringBuilder sql,
                List<String> columns
        ) {

            for (int i = 0;
                 i < columns.size();
                 i++) {

                if (i > 0) {
                    sql.append(", ");
                }

                sql.append(
                        quote(columns.get(i))
                );
            }
        }

        String header(String title) {

            return
                    "-- =====================================================\n" +
                    "-- " + title + "\n" +
                    "-- Generated by SQL Server DDL Extractor\n" +
                    "-- =====================================================\n\n";
        }

        void write(
                Path file,
                StringBuilder sql
        ) throws IOException {

            Files.writeString(
                    file,
                    sql.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        }

        void cleanOutputDirectory(
                Path output
        ) throws IOException {

            Files.createDirectories(output);

            try (DirectoryStream<Path> stream =
                         Files.newDirectoryStream(output)) {

                for (Path path : stream) {

                    if (Files.isRegularFile(path)
                            && path.toString()
                                  .endsWith(".sql")) {

                        Files.delete(path);
                    }
                }
            }
        }

        Long getLongObject(
                ResultSet rs,
                String column
        ) throws SQLException {

            long value =
                    rs.getLong(column);

            if (rs.wasNull()) {
                return null;
            }

            return value;
        }

        String rsString(
                ResultSet rs,
                String column
        ) {

            try {
                return rs.getString(column);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        boolean rsBool(
                ResultSet rs,
                String column
        ) {

            try {
                return rs.getBoolean(column);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        String escapeSqlString(String value) {

            return value.replace("'", "''");
        }
    }

    /*
     * ============================================================
     * Data Model
     * ============================================================
     */

    static class Table {

        String schema;
        String name;

        List<Column> columns =
                new ArrayList<>();

        Table(
                String schema,
                String name
        ) {
            this.schema = schema;
            this.name = name;
        }
    }

    static class Column {

        String name;
        String dataType;

        int maxLength;
        int precision;
        int scale;

        boolean nullable;
        boolean identity;

        Long seed;
        Long increment;

        String defaultDefinition;
    }

    static class PrimaryKey {

        String schema;
        String table;
        String name;

        List<String> columns =
                new ArrayList<>();
    }

    static class UniqueConstraint {

        String schema;
        String table;
        String name;

        List<String> columns =
                new ArrayList<>();
    }

    static class CheckConstraint {

        String schema;
        String table;
        String name;
        String definition;
    }

    static class ForeignKey {

        String schema;
        String table;
        String name;

        String referencedSchema;
        String referencedTable;

        List<ColumnMapping> columns =
                new ArrayList<>();
    }

    static class ColumnMapping {

        String localColumn;
        String referencedColumn;

        ColumnMapping(
                String localColumn,
                String referencedColumn
        ) {
            this.localColumn =
                    localColumn;

            this.referencedColumn =
                    referencedColumn;
        }
    }

    static class IndexInfo {

        String schema;
        String table;
        String name;
        String type;

        boolean unique;

        List<String> columns =
                new ArrayList<>();
    }

    static class ViewInfo {

        String schema;
        String name;
        String definition;
    }

    static class ProgrammableObject {

        String schema;
        String name;
        String type;
        String definition;
    }

    static class Dependency {

        String referencingSchema;
        String referencingObject;

        String referencedSchema;
        String referencedObject;
        String referencedColumn;
    }
}