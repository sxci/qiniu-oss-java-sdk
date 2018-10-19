package com.aliyun.oss;

import com.aliyun.oss.model.*;
import com.qiniu.common.Zone;
import com.qiniu.storage.Configuration;
import com.qiniu.util.Etag;
import junit.framework.Assert;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class QiniuOSSTest {
    protected static final String BUCKET_NAME_PREFIX = "oss-java-sdk-";

    OkHttpClient client = new OkHttpClient();
    QiniuOSSClient qiniuOSSClient;

    private void init0() {
        Configuration.defaultUcHost = "uc-qos.tc2.echosoul.cn";
        Configuration.defaultApiHost = "api-qos.tc2.echosoul.cn";
        Configuration.defaultRsHost = "rs-qos.tc2.echosoul.cn";

        Zone zone = new Zone.Builder(Zone.zone0()).region("z0").upHttp("http://up-qos.tc2.echosoul.cn").
                upBackupHttp("http://up-qos.tc2.echosoul.cn").iovipHttp("http://io-qos.tc2.echosoul.cn").
                rsHttp("http://rs-qos.tc2.echosoul.cn").rsfHttp("http://rsf-qos.tc2.echosoul.cn").build();
        Configuration config = new Configuration(zone);
        config.useHttpsDomains = false; // 使用 http ，方便必要时 http 抓包查看请求响应信息

        String testAccessKey = System.getenv("QINIU_ACCESS_KEY");
        String testSecretKey = System.getenv("QINIU_SECRET_KEY");

        qiniuOSSClient = new QiniuOSSClient(testAccessKey, testSecretKey, config);
    }


    private void init1() {
        //valid ak & sk
        String testAccessKey = System.getenv("QINIU_ACCESS_KEY");
        String testSecretKey = System.getenv("QINIU_SECRET_KEY");

        Configuration config = new Configuration(Zone.zone0());
        config.useHttpsDomains = false;// 使用 http ，方便必要时 http 抓包查看请求响应信息
        
        qiniuOSSClient = new QiniuOSSClient(testAccessKey, testSecretKey, config);
    }

    @Before
    public void setUp() throws Exception {
        init1();
    }

    @After
    public void tearDown() {
        println("\n\ntearDown");
        clean();
    }


    @Test
    public void testQiniu() throws IOException {
        // 1
        println(1);
        List<File> files = createFiles(10);

        // 2
        println(2);
        int bktSize = 5;
        List<String> bktNames = createBuckets(bktSize);
        println(bktNames);

        // 3
        println(3);
        List<Bucket> remoteBkts = qiniuOSSClient.listBuckets();
        listBuckets(remoteBkts);
        checkBucket(bktSize, bktNames, remoteBkts);

        // 4
        println(4);
        qiniuOSSClient.setBucketAcl(bktNames.get(0), CannedAccessControlList.Private);
        qiniuOSSClient.setBucketAcl(bktNames.get(1), CannedAccessControlList.Private);

        //5
        println(5);
        showAndCheckBucketACL(bktNames);

        // 6
        println(6);
        uploadFiles(files, bktNames);

        // 7
        println(7);
        showAndCheckFiles(files, bktNames);

        // 8
        println(8);
        delPartFile(files, bktNames);

        // 9
        println(9);
        showAndCheckFiles(files, bktNames);

        // 10
        println(10);
        showUrls(bktNames);

        // 11
        println(11);
        delAllObjs(bktNames.get(4)); // TODO 完善 删除空间全部文件的方法，但此方法没有做要求
        delAllObjs(bktNames.get(4));
        qiniuOSSClient.deleteBucket(bktNames.get(4));

        try {
            qiniuOSSClient.deleteBucket(bktNames.get(3));
            Assert.fail(" 如果空间还有文件，不能直接删除空间 ");
        } catch (Exception e) {
            println(" 11 下面异常属于正常打印 ");
            e.printStackTrace();
        }

        // 12
        println(12);
        sdkDownloadAndCheckFile(bktNames.get(1), files.get(0)); // bucket2 删除 file2、file3 // 命名从 1 开始
        try {
            sdkDownloadAndCheckFile(bktNames.get(1), files.get(1));
            Assert.fail(" 文件不存在，应该报异常 ");
        } catch (Exception e) {
            println(" 12 下面异常属于正常打印 ");
            e.printStackTrace();
        }

        // 13
        println(13);
        urlDownloadAndCheckFile(bktNames.get(1), files.get(0));

        // 14
        println(14);
        try {
            sdkDownloadAndCheckFile(bktNames.get(4), files.get(1));
            Assert.fail(" 空间不存在，应该报异常 ");
        } catch (Exception e) {
            println(" 14 1 下面异常属于正常打印 ");
            e.printStackTrace();
        }
        try {
            urlDownloadAndCheckFile(bktNames.get(4), files.get(1));
            Assert.fail(" 空间不存在，应该报异常 ");
        } catch (Exception e) {
            println(" 14 2 下面异常属于正常打印 ");
            e.printStackTrace();
        }

        // 15
        println(15);
        // mkbucketv2 接口创建空间，默认开启了首页
        setWebsite(bktNames.get(0), true);
        setWebsite(bktNames.get(1), false);
        String d = bktNames.get(4);
        listBktWebsite(bktNames.subList(0, 4)); // idx 4 已经删除
        try {
            qiniuOSSClient.getBucketWebsite(d);
        } catch (Exception e) {
            println(" 15 下面异常属于正常打印 ");
            e.printStackTrace();
        }

    }


    private List<File> createFiles(int size) throws IOException {
        List<File> files = new ArrayList<>(size);
//        IntStream.range(0, size).forEachOrdered(n->{
//            File file = createSampleFile("_file_" + n, 1024 * 1024 * 5);
//            files.add(file);
//        });
        for (int n = 0; n < size; n++) {
            File file = createSampleFile("file" + (n + 1) + "__", 1024 * 1024 * 5);
            files.add(file);
        }
        return files;
    }

    private List<String> createBuckets(int size) {
        List<String> list = new ArrayList<>(size);
        for (int n = 0; n < size; n++) {
            Bucket i = qiniuOSSClient.createBucket(BUCKET_NAME_PREFIX + "bucket" + (n + 1));
            list.add(i.getName());
        }
        return list;
    }


    private void checkBucket(int bktSize, List<String> bktNames, List<Bucket> remoteBkts) {
        int count = 0;
        for (Bucket bkt : remoteBkts) {
            if (bkt.getName().startsWith(BUCKET_NAME_PREFIX)) {
                Assert.assertTrue(" 空间名应在新建的列表中： " + bkt.getName(), bktNames.contains(bkt.getName()));
                count++;
            }
        }
        Assert.assertEquals("新建空间列表应等于 " + bktSize, bktSize, count);
    }


    private void showAndCheckBucketACL(List<String> bktNames) {
        for (int n = 0; n < bktNames.size(); n++) {
            AccessControlList al = qiniuOSSClient.getBucketAcl(bktNames.get(n));
            CannedAccessControlList acl = al.getCannedACL();
            println(bktNames.get(n) + ": " + acl);
            if (n < 2) {
                Assert.assertEquals(" 第一个、第二个空间设置为私有空间 ", CannedAccessControlList.Private, acl);
            } else {
                Assert.assertNotSame(" 除第一个、第二个空间外，默认为公开空间 ", CannedAccessControlList.Private, acl);
            }
        }
    }


    private void uploadFiles(List<File> files, List<String> bktNames) {
        for (String bkt : bktNames) {
            println("");
            for (File f : files) {
                String key = f.getName();
                PutObjectResult ret = qiniuOSSClient.putObject(bkt, key, f);
                println(bkt + ": " + key + " : " + ret.getETag());
            }
        }
    }

    private void showAndCheckFiles(List<File> files, List<String> bktNames) {
        println("\n");
        for (String bkt : bktNames) {
            println(bkt);
            ObjectListing all = qiniuOSSClient.listObjects(bkt);
            List<OSSObjectSummary> objs = all.getObjectSummaries();
            List<String> randomKeys = new ArrayList<>(7);
            for (OSSObjectSummary o : objs) {
                println("\t key: " + o.getKey() + ", hash: " + o.getETag() + ", type: " + o.getStorageClass());
                Assert.assertTrue(" 文件名在上传文件列表中 ", hasFile(o, files));
            }
        }
        println("\n");
    }


    private boolean hasFile(OSSObjectSummary o, List<File> files) {
        for (File fo : files) {
            if (o.getKey().equals(fo.getName())) {
                return true;
            }
        }
        return false;
    }


    // 空间数为 5 ，文件数为 10 ，下标不会越界
    private void delPartFile(List<File> files, List<String> bktNames) {
        for (int i = 0; i < bktNames.size(); i++) {
            qiniuOSSClient.deleteObject(bktNames.get(i), files.get(i).getName());
            qiniuOSSClient.deleteObject(bktNames.get(i), files.get(i + 1).getName());
        }
    }


    private void showUrls(List<String> bktNames) {
        Date expr = new Date(new Date().getTime() + 3600 * 1000);
        println("\n");
        for (String bkt : bktNames) {
            println(bkt);
            ObjectListing all = qiniuOSSClient.listObjects(bkt);
            List<OSSObjectSummary> objs = all.getObjectSummaries();
            List<String> randomKeys = new ArrayList<>(7);
            for (OSSObjectSummary o : objs) {
                URL url = qiniuOSSClient.generatePresignedUrl(bkt, o.getKey(), expr);
                println(url.toString());
            }
        }
        println("\n");
    }


    private void sdkDownloadAndCheckFile(String bkt, File file) throws IOException {
        GetObjectRequest getReq = new GetObjectRequest(bkt, file.getName());
        File tmp = File.createTempFile("_tmp_file1", ".txt");
        qiniuOSSClient.getObject(getReq, tmp);

        Assert.assertEquals(" 文件内容要求完全一样 ", Etag.file(file), Etag.file(tmp));
        tmp.deleteOnExit();
    }


    private void urlDownloadAndCheckFile(String bkt, File file) throws IOException {
        Date expr = new Date(new Date().getTime() + 3600 * 1000);
        URL url = qiniuOSSClient.generatePresignedUrl(bkt, file.getName(), expr);
        Request request = new Request.Builder()
                .url(url)
                .build();
        Response res = client.newCall(request).execute();
        Assert.assertEquals(" 文件内容要求完全一样 ", Etag.file(file),
                Etag.data(res.body().bytes()));
    }

    private void setWebsite(String bkt, boolean on) {
        SetBucketWebsiteRequest webreq = new SetBucketWebsiteRequest(bkt);
        if (on) {
            webreq.setErrorDocument("error-404");
            webreq.setIndexDocument("index.html");
        }
        qiniuOSSClient.setBucketWebsite(webreq);
    }


    private void listBktWebsite(List<String> bktNames) {
        println("\n");
        for (String bkt : bktNames) {
            BucketWebsiteResult website = qiniuOSSClient.getBucketWebsite(bkt);
            println(bkt + ": " + website.getIndexDocument() + ",   " + website.getErrorDocument());
        }
        println("\n");
    }


    private void listBuckets(List<Bucket> bkts) {
        for (Bucket b : bkts) {
            println(b.getName());
        }
    }

    @Test
    public void clean() {
        List<Bucket> bkts = qiniuOSSClient.listBuckets();
        for (Bucket bkt : bkts) {
            if (bkt.getName().startsWith(BUCKET_NAME_PREFIX)) {
                println(bkt.getName() + ",  delAllObjs");
                delAllObjs(bkt.getName());
                delAllObjs(bkt.getName());
                delAllObjs(bkt.getName());
                println("deleteBucket: " + bkt.getName());
                try {
                    qiniuOSSClient.deleteBucket(bkt.getName());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private void delAllObjs(String bkt) {
        ObjectListing objs = qiniuOSSClient.listObjects(bkt);
        DeleteObjectsRequest dels = new DeleteObjectsRequest(bkt);
        List<String> keys = new ArrayList<String>();
        if (objs.getObjectSummaries().size() == 0) {
            return;
        }
        for (OSSObjectSummary obj : objs.getObjectSummaries()) {
            keys.add(obj.getKey());
        }
        dels.setKeys(keys);
        qiniuOSSClient.deleteObjects(dels);
    }


    private static void println(Object obj) {
        System.out.println(obj);
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
