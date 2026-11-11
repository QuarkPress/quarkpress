package org.quarkpress.media.storage.s3.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * An {@link S3Client} whose answers a test scripts, one handler per operation; it records every request in order, and
 * an operation without a handler fails the test.
 */
final class ScriptedS3Client implements S3Client {

    /** Every request received, in order. */
    final List<Object> requests = new ArrayList<>();

    Function<PutObjectRequest, PutObjectResponse> putObject;
    Function<HeadObjectRequest, HeadObjectResponse> headObject;
    Function<GetObjectRequest, ResponseInputStream<GetObjectResponse>> getObject;
    Function<ListObjectsV2Request, ListObjectsV2Response> listObjectsV2;
    Function<DeleteObjectRequest, DeleteObjectResponse> deleteObject;

    /** An S3 error answer with an HTTP status and an S3 error code. */
    static S3Exception error(int status, String code) {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .message(code)
                .build();
    }

    /** The answer with a body for a missing bucket, as the SDK reads it (a HEAD answer never gives it). */
    static NoSuchBucketException noSuchBucket() {
        return (NoSuchBucketException) NoSuchBucketException.builder()
                .statusCode(404)
                .awsErrorDetails(
                        AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                .message("NoSuchBucket")
                .build();
    }

    /** The answer for a key that holds no object. */
    static NoSuchKeyException noSuchKey() {
        return NoSuchKeyException.builder().statusCode(404).build();
    }

    /** The requests of one type, in order. */
    <T> List<T> requests(Class<T> type) {
        return requests.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Override
    public String serviceName() {
        return "s3";
    }

    @Override
    public void close() {}

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        return answer("putObject", putObject, request);
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest request) {
        return answer("headObject", headObject, request);
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
        return answer("getObject", getObject, request);
    }

    @Override
    public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
        return answer("listObjectsV2", listObjectsV2, request);
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
        return answer("deleteObject", deleteObject, request);
    }

    private <Q, R> R answer(String operation, Function<Q, R> handler, Q request) {
        requests.add(request);
        if (handler == null) {
            throw new AssertionError("no answer scripted for " + operation + ": " + request);
        }
        return handler.apply(request);
    }
}
