package com.aliyun.oss;

import com.aliyun.oss.model.*;
import com.qiniu.common.Zone;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Etag;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Random;


public class QiniuTest {
    protected static final String BUCKET_NAME_PREFIX = "oss-java-sdk-";

    //valid ak & sk
    public static String testAccessKey = System.getenv("QINIU_ACCESS_KEY");
    public static String testSecretKey = System.getenv("QINIU_SECRET_KEY");

    //z0
    public static final String testBucket_z0 = "javasdk";
    public static final String testKey_z0 = "java-duke.png";

    protected String bucketName;
    private String key;
    private File tempFile1;
    private File tempFile2;

    QiniuOSSClient qiniuOSSClient;

    @Before
    public void setUp() throws Exception {
        Configuration config = new Configuration(Zone.zone0());
        qiniuOSSClient = new QiniuOSSClient(testAccessKey, testSecretKey, config);
        tempFile1 = createSampleFile("_oss-java-sdk_qiniu_temp", 234539);
        tempFile2 = File.createTempFile("_oss-java-qiniu2", ".temp");
        key = "oss-java-sdk_/" + new Date().getTime() / 100 + "_" + new Random().nextInt(50000);
        System.out.println(bucketName);
        System.out.println(tempFile1.getAbsolutePath());
        System.out.println(key);
    }

    @After
    public void tearDown() {
        try {
            qiniuOSSClient.deleteBucket(bucketName);
        } catch (Exception e) {
//            e.printStackTrace();
        }
        qiniuOSSClient.shutdown();
        tempFile1.delete();
        if (tempFile2 != null) {
            tempFile2.delete();
        }
    }



    @Test
    public void testQiniu() throws IOException {
        bucketName = createBucket();
        listBucket(true);
        putObject();
        listObj(true);
        getObj();
        getObj();
        delObject();
        try {
            Thread.sleep(4 * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        getObj404();
        listObj(false);
        delBucket();
        listBucket(false);
    }


    private String createBucket() {
        long ticks = new Date().getTime() / 1000 + new Random().nextInt(5000);
        String bucketName = BUCKET_NAME_PREFIX + ticks;
        qiniuOSSClient.createBucket(bucketName);
        return bucketName;
    }

    private void delBucket() {
        qiniuOSSClient.deleteBucket(bucketName);
    }


    public void listBucket(boolean hope) {
        List<Bucket> bkts = qiniuOSSClient.listBuckets();
        boolean hasNewBucket = false;
        for (Bucket b : bkts) {
            if (bucketName.equalsIgnoreCase(b.getName())) {
                hasNewBucket = true;
                break;
            }
        }
        Assert.assertEquals(" 找到新建的空间 ", hope, hasNewBucket);
    }


    public void putObject() throws IOException {
        PutObjectResult ret = qiniuOSSClient.putObject(bucketName, key, tempFile1);
        Assert.assertEquals(ret.getETag(), Etag.file(tempFile1));
    }

    public void getObj() throws IOException {
        GetObjectRequest getReq = new GetObjectRequest(bucketName, key);
        qiniuOSSClient.getObject(getReq, tempFile2);
        Assert.assertEquals(Etag.file(tempFile1), Etag.file(tempFile2));
    }

    public void getObj404() throws IOException {
        GetObjectRequest getReq = new GetObjectRequest(bucketName, key);
        try {
            qiniuOSSClient.getObject(getReq, tempFile2);
        } catch (OSSException e) {
            Assert.assertEquals("404", e.getErrorCode());
        }
    }

    public void delObject() throws IOException {
        qiniuOSSClient.deleteObject(bucketName, key);
    }

    public void listObj(boolean hope) {
        ObjectListing l = qiniuOSSClient.listObjects(bucketName, "oss-java-sdk_/");
        boolean hasNewFile = false;
        for (OSSObjectSummary obj : l.getObjectSummaries()) {
            if (key.equalsIgnoreCase(obj.getKey())) {
                hasNewFile = true;
                break;
            }
        }
        Assert.assertEquals(" 找到新建的文件 ", hope, hasNewFile);
    }

    @Test
    public void testGeneratePresignedUrl() {
       URL url =  qiniuOSSClient.generatePresignedUrl("javasdk", "test.jpg",
                new Date(new Date().getTime() + 3600 * 1000));
       System.out.println(url.toString());
    }




    public static File createSampleFile(String fileName, long size) throws IOException {
        File file = File.createTempFile(fileName, ".txt");
        file.deleteOnExit();
        String context = "abcdefghijklmnopqrstuvwxyz0123456789011234567890\n";

        Writer writer = new OutputStreamWriter(new FileOutputStream(file));
        for (int i = 0; i < size / context.length(); i++) {
            writer.write(context);
        }
        writer.close();

        return file;
    }
}
