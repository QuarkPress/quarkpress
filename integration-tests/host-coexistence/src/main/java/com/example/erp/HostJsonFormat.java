package com.example.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.JsonFormat;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;

/**
 * The host's JSON columns use the application's own, customized ObjectMapper. Quarkus asks a host that customizes its
 * ObjectMapper to say so with a FormatMapper for its default persistence unit; QuarkPress's unit has another one.
 */
@JsonFormat
@PersistenceUnitExtension
@ApplicationScoped
public class HostJsonFormat implements FormatMapper {

    private final FormatMapper delegate;

    @Inject
    public HostJsonFormat(ObjectMapper hostMapper) {
        this.delegate = new JacksonJsonFormatMapper(hostMapper);
    }

    @Override
    public <T> T fromString(CharSequence text, JavaType<T> javaType, WrapperOptions options) {
        return delegate.fromString(text, javaType, options);
    }

    @Override
    public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
        return delegate.toString(value, javaType, options);
    }
}
