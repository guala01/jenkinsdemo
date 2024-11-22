package com.cloudemail.cloud.email;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.cloudemail.cloud.email.service.S3Service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class S3ServiceTest {

    @MockBean
    private AmazonS3 amazonS3;

    @Autowired
    private S3Service s3Service;

    @Test
    void getFileContentShouldReturnContent() {
        // Arrange
        String expectedContent = "test content";
        S3Object s3Object = new S3Object();
        s3Object.setObjectContent(new S3ObjectInputStream(
            new ByteArrayInputStream(expectedContent.getBytes()),
            null
        ));
        
        when(amazonS3.getObject(anyString(), anyString())).thenReturn(s3Object);
        
        // Act
        String result = s3Service.getFileContent("test.json");
        
        // Assert
        assertEquals(expectedContent, result.trim());
    }
}
