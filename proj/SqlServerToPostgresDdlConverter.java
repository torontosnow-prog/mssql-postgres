import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlServerToPostgresDdlConverter {

    /*
     * ============================================================
     * Main
     * ============================================================
     *
     * Usage:
     *
     *   java SqlServerToPostgresDdlConverter input.sql output.sql
     *
     */

    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.err.println(
                    "Usage: java SqlServerToPostgresDdlConverter " +
                    "<input.sql> <output.sql>"
            );
            System.exit(1);
        }

        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        convertFile(input, output);

        System.out.println(
                "Conversion completed:"
        );

        System.out.println(
                "Input : " + input.toAbsolutePath()
        );

        System.out.println(
                "Output: " + output.toAbsolutePath()
        );
    }

    /*
     * ============================================================
     * File conversion
     * ============================================================
     */

    public static void convertFile(
            Path input,
            Path output
    ) throws IOException {

        String sql = Files.readString(
                input,
                StandardCharsets.UTF_8
        );

        String converted =
                convert(sql);

        Files.writeString(
                output,
                converted,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    /*
     * ============================================================
     * Main conversion pipeline
     * ============================================================
     */

    public static String convert(String sql) {

        if (sql == null || sql.isBlank()) {
            return "";
        }

        /*
         * Normalize line endings.
         */
        sql = sql.replace("\r\n", "\n");
        sql = sql.replace("\r", "\n");

        /*
         * Remove SQL Server batch separators.
         */
        sql = removeGoStatements(sql);

        /*
         * Remove SQL Server USE statements.
         */
        sql = removeUseStatements(sql);

        /*
         * Remove SQL Server SET statements.
         */
        sql = removeSqlServerSetStatements(sql);

        /*
         * Convert identifiers.
         */
        sql = convertIdentifiers(sql);

        /*
         * Convert data types.
         */
        sql = convertDataTypes(sql);

        /*
         * Convert identity columns.
         */
        sql = convertIdentity(sql);

        /*
         * Convert SQL Server functions / expressions.
         */
        sql = convertFunctions(sql);

        /*
         * Convert SQL Server DEFAULT syntax.
         */
        sql = convertDefaults(sql);

        /*
         * Remove SQL Server-specific index/table options.
         */
        sql = removeSqlServerOptions(sql);

        /*
         * Convert ALTER TABLE.
         */
        sql = convertAlterTable(sql);

        /*
         * Convert CREATE TABLE details.
         */
        sql = convertCreateTable(sql);

        /*
         * Convert common SQL Server syntax remaining in DDL.
         */
        sql = convertCommonSyntax(sql);

        /*
         * Remove empty statements.
         */
        sql = removeEmptyStatements(sql);

        /*
         * Normalize whitespace.
         */
        sql = normalizeWhitespace(sql);

        return addHeader(sql);
    }

    /*
     * ============================================================
     * GO
     * ============================================================
     */

    private static String removeGoStatements(
            String sql
    ) {

        return sql.replaceAll(
                "(?im)^\\s*GO\\s*;?\\s*$",
                ""
        );
    }

    /*
     * ============================================================
     * USE database
     * ============================================================
     */

    private static String removeUseStatements(
            String sql
    ) {

        return sql.replaceAll(
                "(?is)\\bUSE\\s+(?:\\[[^]]+\\]|\\w+)\\s*;?",
                ""
        );
    }

    /*
     * ============================================================
     * SET statements
     * ============================================================
     */

    private static String removeSqlServerSetStatements(
            String sql
    ) {

        return sql.replaceAll(
                "(?im)^\\s*SET\\s+" +
                "(?:ANSI_NULLS|QUOTED_IDENTIFIER|" +
                "ANSI_PADDING|ANSI_WARNINGS|CONCAT_NULL_YIELDS_NULL|" +
                "ARITHABORT|NUMERIC_ROUNDABORT)" +
                "\\s+(?:ON|OFF)\\s*;?\\s*$",
                ""
        );
    }

    /*
     * ============================================================
     * Identifier conversion
     *
     * [dbo].[Customer] -> "dbo"."Customer"
     * [Customer]       -> "Customer"
     * ============================================================
     */

    private static String convertIdentifiers(
            String sql
    ) {

        Pattern p =
                Pattern.compile(
                        "\\[([^]]+)\\]"
                );

        Matcher m =
                p.matcher(sql);

        StringBuffer result =
                new StringBuffer();

        while (m.find()) {

            String identifier =
                    m.group(1);

            identifier =
                    identifier.replace(
                            "\"",
                            "\"\""
                    );

            m.appendReplacement(
                    result,
                    Matcher.quoteReplacement(
                            "\"" + identifier + "\""
                    )
            );
        }

        m.appendTail(result);

        return result.toString();
    }

    /*
     * ============================================================
     * Data types
     * ============================================================
     */

    private static String convertDataTypes(
            String sql
    ) {

        /*
         * Order is important.
         */

        sql = replaceType(
                sql,
                "\\bnvarchar\\s*\\(\\s*max\\s*\\)",
                "text"
        );

        sql = replaceType(
                sql,
                "\\bnvarchar\\s*\\(\\s*(\\d+)\\s*\\)",
                "varchar($1)"
        );

        sql = replaceType(
                sql,
                "\\bnchar\\s*\\(\\s*(\\d+)\\s*\\)",
                "char($1)"
        );

        sql = replaceType(
                sql,
                "\\bvarchar\\s*\\(\\s*max\\s*\\)",
                "text"
        );

        sql = replaceType(
                sql,
                "\\bvarbinary\\s*\\(\\s*max\\s*\\)",
                "bytea"
        );

        sql = replaceType(
                sql,
                "\\bvarbinary\\s*\\(\\s*(\\d+)\\s*\\)",
                "bytea"
        );

        sql = replaceType(
                sql,
                "\\bbinary\\s*\\(\\s*(\\d+)\\s*\\)",
                "bytea"
        );

        sql = replaceType(
                sql,
                "\\bntext\\b",
                "text"
        );

        sql = replaceType(
                sql,
                "\\btext\\b",
                "text"
        );

        sql = replaceType(
                sql,
                "\\bimage\\b",
                "bytea"
        );

        sql = replaceType(
                sql,
                "\\buniqueidentifier\\b",
                "uuid"
        );

        sql = replaceType(
                sql,
                "\\btinyint\\b",
                "smallint"
        );

        sql = replaceType(
                sql,
                "\\bsmallmoney\\b",
                "numeric(10,4)"
        );

        sql = replaceType(
                sql,
                "\\bmoney\\b",
                "numeric(19,4)"
        );

        sql = replaceType(
                sql,
                "\\bdatetime2\\s*\\(\\s*\\d+\\s*\\)",
                "timestamp"
        );

        sql = replaceType(
                sql,
                "\\bdatetime2\\b",
                "timestamp"
        );

        sql = replaceType(
                sql,
                "\\bdatetimeoffset\\b",
                "timestamp with time zone"
        );

        sql = replaceType(
                sql,
                "\\bsmalldatetime\\b",
                "timestamp(0)"
        );

        sql = replaceType(
                sql,
                "\\bdatetime\\b",
                "timestamp"
        );

        sql = replaceType(
                sql,
                "\\bbit\\b",
                "boolean"
        );

        sql = replaceType(
                sql,
                "\\bfloat\\b",
                "double precision"
        );

        sql = replaceType(
                sql,
                "\\breal\\b",
                "real"
        );

        sql = replaceType(
                sql,
                "\\bdecimal\\b",
                "numeric"
        );

        sql = replaceType(
                sql,
                "\\bnumeric\\b",
                "numeric"
        );

        return sql;
    }

    private static String replaceType(
            String sql,
            String regex,
            String replacement
    ) {

        return Pattern.compile(
                regex,
                Pattern.CASE_INSENSITIVE
        ).matcher(sql).replaceAll(
                replacement
        );
    }

    /*
     * ============================================================
     * IDENTITY
     *
     * SQL Server:
     *
     *   ID int IDENTITY(1,1) NOT NULL
     *
     * PostgreSQL:
     *
     *   ID integer GENERATED BY DEFAULT AS IDENTITY NOT NULL
     * ============================================================
     */

    private static String convertIdentity(
            String sql
    ) {

        Pattern pattern =
                Pattern.compile(
                        "\\s+IDENTITY\\s*\\(" +
                        "\\s*([^,]+)\\s*,\\s*([^\\)]+)" +
                        "\\s*\\)",
                        Pattern.CASE_INSENSITIVE
                );

        Matcher matcher =
                pattern.matcher(sql);

        StringBuffer result =
                new StringBuffer();

        while (matcher.find()) {

            String seed =
                    matcher.group(1).trim();

            String increment =
                    matcher.group(2).trim();

            String replacement;

            if ("1".equals(seed)
                    && "1".equals(increment)) {

                replacement =
                        " GENERATED BY DEFAULT AS IDENTITY";

            } else {

                /*
                 * PostgreSQL identity columns support
                 * START WITH and INCREMENT BY.
                 */

                replacement =
                        " GENERATED BY DEFAULT AS IDENTITY " +
                        "(START WITH " +
                        seed +
                        " INCREMENT BY " +
                        increment +
                        ")";
            }

            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(
                            replacement
                    )
            );
        }

        matcher.appendTail(result);

        return result.toString();
    }

    /*
     * ============================================================
     * Functions
     * ============================================================
     */

    private static String convertFunctions(
            String sql
    ) {

        sql = replaceFunction(
                sql,
                "GETDATE\\s*\\(\\s*\\)",
                "CURRENT_TIMESTAMP"
        );

        sql = replaceFunction(
                sql,
                "GETUTCDATE\\s*\\(\\s*\\)",
                "CURRENT_TIMESTAMP"
        );

        sql = replaceFunction(
                sql,
                "SYSDATETIME\\s*\\(\\s*\\)",
                "CURRENT_TIMESTAMP"
        );

        sql = replaceFunction(
                sql,
                "SYSUTCDATETIME\\s*\\(\\s*\\)",
                "CURRENT_TIMESTAMP"
        );

        sql = replaceFunction(
                sql,
                "NEWID\\s*\\(\\s*\\)",
                "gen_random_uuid()"
        );

        sql = replaceFunction(
                sql,
                "ISNULL\\s*\\(",
                "COALESCE("
        );

        return sql;
    }

    private static String replaceFunction(
            String sql,
            String regex,
            String replacement
    ) {

        return Pattern.compile(
                regex,
                Pattern.CASE_INSENSITIVE
        ).matcher(sql).replaceAll(
                replacement
        );
    }

    /*
     * ============================================================
     * DEFAULT constraints
     *
     * SQL Server often generates:
     *
     * CONSTRAINT [DF_x] DEFAULT (...) FOR [Column]
     *
     * PostgreSQL wants:
     *
     * DEFAULT (...)
     *
     * as part of CREATE TABLE or an ALTER COLUMN.
     *
     * For migration, we remove the SQL Server "FOR column"
     * form and leave DEFAULT available for later processing.
     * ============================================================
     */

    private static String convertDefaults(
            String sql
    ) {

        /*
         * Remove:
         *
         * FOR "Column"
         */

        sql = sql.replaceAll(
                "(?i)\\s+FOR\\s+\"[^\"]+\"",
                ""
        );

        /*
         * SQL Server's named default constraint:
         *
         * CONSTRAINT "DF_x" DEFAULT ...
         *
         * PostgreSQL does not use this form for column defaults.
         */

        sql = sql.replaceAll(
                "(?i)\\s+CONSTRAINT\\s+\"[^\"]+\"\\s+" +
                "(?=DEFAULT)",
                " "
        );

        return sql;
    }

    /*
     * ============================================================
     * ALTER TABLE
     * ============================================================
     */

    private static String convertAlterTable(
            String sql
    ) {

        /*
         * SQL Server:
         *
         * ALTER TABLE [dbo].[Customer]
         *
         * becomes:
         *
         * ALTER TABLE "dbo"."Customer"
         *
         * Identifier conversion already handled this.
         */

        /*
         * SQL Server:
         *
         * ALTER TABLE x ADD CONSTRAINT y
         * DEFAULT (...) FOR column
         *
         * PostgreSQL needs:
         *
         * ALTER TABLE x
         * ALTER COLUMN column SET DEFAULT (...);
         *
         * Convert common generated form.
         */

        Pattern defaultPattern =
                Pattern.compile(
                        "(?is)" +
                        "ALTER\\s+TABLE\\s+" +
                        "(\"[^\"]+\"(?:\\.\"[^\"]+\")?)\\s+" +
                        "ADD\\s+" +
                        "(?:CONSTRAINT\\s+\"[^\"]+\"\\s+)?" +
                        "DEFAULT\\s+" +
                        "(.+?)\\s+" +
                        "FOR\\s+\"([^\"]+)\"\\s*;?",
                        Pattern.CASE_INSENSITIVE
                );

        Matcher m =
                defaultPattern.matcher(sql);

        StringBuffer result =
                new StringBuffer();

        while (m.find()) {

            String table =
                    m.group(1);

            String defaultValue =
                    m.group(2).trim();

            String column =
                    m.group(3);

            String replacement =
                    "ALTER TABLE " +
                    table +
                    " ALTER COLUMN \"" +
                    column +
                    "\" SET DEFAULT " +
                    defaultValue +
                    ";";

            m.appendReplacement(
                    result,
                    Matcher.quoteReplacement(
                            replacement
                    )
            );
        }

        m.appendTail(result);

        sql = result.toString();

        /*
         * SQL Server:
         *
         * ALTER TABLE x ALTER COLUMN y varchar(100) NULL
         *
         * PostgreSQL:
         *
         * ALTER TABLE x ALTER COLUMN y TYPE varchar(100);
         *
         */

        Pattern alterColumn =
                Pattern.compile(
                        "(?im)" +
                        "ALTER\\s+TABLE\\s+" +
                        "(\"[^\"]+\"(?:\\.\"[^\"]+\")?)\\s+" +
                        "ALTER\\s+COLUMN\\s+" +
                        "\"([^\"]+)\"\\s+" +
                        "([^;\\n]+)",
                        Pattern.CASE_INSENSITIVE
                );

        Matcher ac =
                alterColumn.matcher(sql);

        StringBuffer alterResult =
                new StringBuffer();

        while (ac.find()) {

            String table =
                    ac.group(1);

            String column =
                    ac.group(2);

            String definition =
                    ac.group(3).trim();

            /*
             * Remove NULL / NOT NULL from type portion.
             */

            boolean notNull =
                    Pattern.compile(
                            "\\bNOT\\s+NULL\\b",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(definition).find();

            definition =
                    definition.replaceAll(
                            "(?i)\\s+(NOT\\s+)?NULL\\b",
                            ""
                    ).trim();

            String replacement =
                    "ALTER TABLE " +
                    table +
                    " ALTER COLUMN \"" +
                    column +
                    "\" TYPE " +
                    definition +
                    ";";

            if (notNull) {

                replacement +=
                        "\nALTER TABLE " +
                        table +
                        " ALTER COLUMN \"" +
                        column +
                        "\" SET NOT NULL;";
            }

            ac.appendReplacement(
                    alterResult,
                    Matcher.quoteReplacement(
                            replacement
                    )
            );
        }

        ac.appendTail(alterResult);

        return alterResult.toString();
    }

    /*
     * ============================================================
     * CREATE TABLE
     * ============================================================
     */

    private static String convertCreateTable(
            String sql
    ) {

        /*
         * Remove SQL Server table options:
         *
         * ) ON [PRIMARY]
         *
         * ) TEXTIMAGE_ON [PRIMARY]
         */

        sql = sql.replaceAll(
                "(?is)" +
                "\\)\\s*" +
                "TEXTIMAGE_ON\\s+" +
                "\"[^\"]+\"",
                ")"
        );

        sql = sql.replaceAll(
                "(?is)" +
                "\\)\\s*" +
                "ON\\s+" +
                "\"[^\"]+\"",
                ")"
        );

        /*
         * Remove clustered/nonclustered keywords.
         *
         * PostgreSQL does not support these keywords
         * in CREATE TABLE constraints.
         */

        sql = sql.replaceAll(
                "(?i)\\bCLUSTERED\\b",
                ""
        );

        sql = sql.replaceAll(
                "(?i)\\bNONCLUSTERED\\b",
                ""
        );

        return sql;
    }

    /*
     * ============================================================
     * SQL Server-specific syntax
     * ============================================================
     */

    private static String convertCommonSyntax(
            String sql
    ) {

        /*
         * SQL Server uses:
         *
         * DEFAULT ((0))
         *
         * PostgreSQL can accept DEFAULT (0), but simplify
         * unnecessary nested parentheses.
         */

        sql = sql.replaceAll(
                "(?i)DEFAULT\\s*\\(\\(([^()]*)\\)\\)",
                "DEFAULT ($1)"
        );

        /*
         * SQL Server bracket identifiers should already have
         * been converted, but handle escaped SQL Server names
         * defensively.
         */

        /*
         * Remove SQL Server FILEGROUP references.
         */

        sql = sql.replaceAll(
                "(?i)\\s+ON\\s+\"[^\"]+\"",
                ""
        );

        /*
         * Remove SQL Server index options:
         *
         * WITH (PAD_INDEX = OFF, ...)
         */

        sql = sql.replaceAll(
                "(?is)\\s+WITH\\s*\\([^)]*\\)",
                ""
        );

        /*
         * Remove SQL Server:
         *
         * WITH (ONLINE = ON)
         */

        sql = sql.replaceAll(
                "(?is)\\s+WITH\\s*\\([^)]*\\)",
                ""
        );

        return sql;
    }

    /*
     * ============================================================
     * Remove empty statements
     * ============================================================
     */

    private static String removeEmptyStatements(
            String sql
    ) {

        sql = sql.replaceAll(
                "(?m)^\\s*;\\s*$",
                ""
        );

        sql = sql.replaceAll(
                "(?m)^\\s*\\n",
                ""
        );

        return sql;
    }

    /*
     * ============================================================
     * Whitespace
     * ============================================================
     */

    private static String normalizeWhitespace(
            String sql
    ) {

        sql = sql.replaceAll(
                "[ \\t]+\\n",
                "\n"
        );

        sql = sql.replaceAll(
                "\\n{3,}",
                "\n\n"
        );

        return sql.trim() + "\n";
    }

    /*
     * ============================================================
     * Header
     * ============================================================
     */

    private static String addHeader(
            String sql
    ) {

        return
                "-- =====================================================\n" +
                "-- PostgreSQL 17 DDL\n" +
                "-- Converted from SQL Server DDL\n" +
                "-- Generated by SqlServerToPostgresDdlConverter\n" +
                "-- =====================================================\n\n" +
                sql;
    }
}