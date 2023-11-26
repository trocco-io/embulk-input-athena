package org.embulk.input.athena.getter;

import org.embulk.input.jdbc.JdbcColumn;
import org.embulk.input.jdbc.getter.ColumnGetterFactory;
import org.embulk.spi.PageBuilder;

import java.time.ZoneId;

import static java.util.Locale.ENGLISH;

public class AthenaColumnGetterFactory extends ColumnGetterFactory {
    public AthenaColumnGetterFactory(PageBuilder to, ZoneId defaultTimeZone) {
        super(to, defaultTimeZone);
    }

    @Override
    protected String sqlTypeToValueType(JdbcColumn column, int sqlType) {
        try {
            return super.sqlTypeToValueType(column, sqlType);
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException(
                    String.format(ENGLISH,
                            "Unsupported type %s (sqlType=%d) of '%s' column. Please add '%s: {value_type: string}' to 'column_options: {...}' option to convert the values to strings.",
                            column.getTypeName(), column.getSqlType(), column.getName(), column.getName()));
        }
    }
}