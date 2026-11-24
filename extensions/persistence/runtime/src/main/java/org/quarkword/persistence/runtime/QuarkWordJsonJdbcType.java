package org.quarkword.persistence.runtime;

import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.type.descriptor.jdbc.JsonJdbcType;

/**
 * Writes JSON values as the text the format mapper produced, without a database conversion that would reorder object
 * keys: PostgreSQL receives {@code cast(? as json)} (json keeps the text; jsonb would not), other databases a plain
 * string (MySQL stores it in a {@code longtext} column checked with {@code json_valid}).
 */
public class QuarkWordJsonJdbcType extends JsonJdbcType {

    public QuarkWordJsonJdbcType() {
        super(null);
    }

    @Override
    public void appendWriteExpression(String writeExpression, SqlAppender appender, Dialect dialect) {
        appendWriteExpression(writeExpression, null, appender, dialect);
    }

    @Override
    public void appendWriteExpression(String writeExpression, Size size, SqlAppender appender, Dialect dialect) {
        if (dialect instanceof PostgreSQLDialect) {
            appender.append("cast(");
            appender.append(writeExpression);
            appender.append(" as json)");
        } else {
            appender.append(writeExpression);
        }
    }

    @Override
    public boolean isWriteExpressionTyped(Dialect dialect) {
        return dialect instanceof PostgreSQLDialect;
    }
}
