package com.invoiceflow.invoice_service.storage;

import com.invoiceflow.invoice_service.config.StorageProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class ObjectStorageService {

    private final S3Client s3Client;
    private final String bucket;

    public ObjectStorageService(S3Client s3, StorageProperties storageProperties) {
        this.s3Client = s3;
        this.bucket = storageProperties.bucket();
    }

    public void putObject(String key, byte[] content, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(content));
    }
}
