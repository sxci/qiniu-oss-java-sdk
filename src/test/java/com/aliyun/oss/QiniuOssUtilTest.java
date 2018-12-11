package com.aliyun.oss;

import com.aliyun.oss.common.utils.HttpUtil;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.util.Auth;
import com.qiniu.util.Etag;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class QiniuOssUtilTest {
    // http://oq6ilmarf.com2.z0.glb.qiniucdn.com/2338_1436560813.jpeg
    String bucket = "liubin";
    String key = "2338_1436560813.jpeg";
    QiniuOssUtil qiniuOssUtil;
    BucketManager bucketManager;

    @Before
    public void setUp() throws Exception {
        String ak = "";
        String sk = "";
        // http://oq6ilmarf.com2.z0.glb.qiniucdn.com  or   oq6ilmarf.com2.z0.glb.qiniucdn.com
        String host = "http://oq6ilmarf.com2.z0.glb.qiniucdn.com";

        qiniuOssUtil = new QiniuOssUtil(ak, sk, host);
        bucketManager = new BucketManager(Auth.create(ak, sk), new Configuration());
    }

    @Test
    public void testOssClientShutDown() {
        // pass
        //// 调用次方法后，各个耗资源的对象设置为 null，
        //// 若调用要其它的方法，会重新新建资源。如果需要报错，可单独提出  //
    }

    @Test
    public void testWwitchOssToken() {
        // pass
        /**
         * 什么都不做，七牛只认 ak sk，额外的 token 没什么意义
         * */
    }

    @Test
    public void testGetMeta() throws ParseException {
        // http://oq6ilmarf.com2.z0.glb.qiniucdn.com/2338_1436560813.jpeg
        ObjectMetadata meta = qiniuOssUtil.getMeta(bucket, key);
        assertNotNull(meta);

        /**
         * 没有完整的对应信息，响应 stat 接口：
         * 当前只有: ContentType, ContentLength, LastModified ，若需要其它信息，请提出需求
         * */

        assertEquals("image/jpeg", meta.getContentType());
        assertEquals(53986, meta.getContentLength());
        assertEquals(new Date(15064371347957620L/10000), meta.getLastModified());
    }


    @Test
    public void testIsBucketExist0() {
        boolean exist = qiniuOssUtil.isBucketExist(bucket);
        assertEquals(true, exist);
    }

    @Test(expected = QiniuOssUtil.OssServiceException.class)
    public void testIsBucketExist1() {
        /**
         * 七牛要求空间需先创建，不存在空间直接抛异常
         * */
        String bkt = bucket + "shrhfgwi_28375";
        qiniuOssUtil.isBucketExist(bkt);
        fail("should throw exception, bucket: \"" + bkt + "\" is not exist.");
    }

    @Test
    public void testCreateBucket() {
        // pass
        /**
         * 阿里 是空间不存在，就新建一个， 一个域名可以访问多个空间内容。
         * 七牛 一个域名只能访问一个空间，域名通过构造方法传入，要求空间、域名都已经配置好在启动服务。
         * 即此方法不会被调用到
         * */
    }


    @Test
    public void testRemoveBucket() {
        // pass
        /**
         * 如果不用新建空间，删除也就不必了
         * */
    }

    @Test
    public void testFetchFile() throws IOException {
        File f = File.createTempFile("qiniu_oss_", "tmp");
        String path = f.getAbsolutePath();
        System.out.println(path);
        qiniuOssUtil.fetchFile(bucket, key, path);
//        f = new File(path);
        assertEquals(53986, f.length());
        f.delete();
    }


    // uploadContent,  fetchContent,  deleteOssObject
    @Test
    public void testUploadFetchContent() throws QiniuException {
        String content = "led to load class \"org.slf4j.impl.StaticLoggerBinder\".\n" +
                "SLF4J: Defaulting to no-operation (NOP) logger implementation\n" +
                "SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.\n" +
                "/var/folders/ld/0_5wyyz96dx4rnq6j3d3p5h40000gn/T/qiniu_7260501609310137976tmp\n";
        String object = "qiniu_oss_test_26050160283jfr393101379";
        String mimetype = "my/mime-type";
        // 没有用到 expire 、 maxage
        String url1 = qiniuOssUtil.uploadContent(bucket, object, content, mimetype, null, null);
        assertEquals("http://oq6ilmarf.com2.z0.glb.qiniucdn.com/" + HttpUtil.urlEncode(object, "UTF-8"), url1);
        String content2 = qiniuOssUtil.fetchContent(bucket, object);
        assertEquals(content, content2);
        FileInfo info = bucketManager.stat(bucket, object);
        assertEquals(mimetype, info.mimeType);
        qiniuOssUtil.deleteOssObject(bucket, object);
        // cdn 缓存，导致还有可能访问已删除的文件
//        String content3 = qiniuOssUtil.fetchContent(bucket, object);
//        assertEquals("", content3);
        try {
            bucketManager.stat(bucket, object);
            fail("should throw exception, file is not exist");
        } catch (QiniuException e) {
            assertEquals(612, e.code());
        }
    }

    @Test
    public void testUploadFile() throws IOException {
        File f = createFile(34529);
        String object = "qiniu_oss_test_26050160283jfr393101379";
        String mimetype = "my/mime-type";
        // 没有用到 expire 、 maxage
        qiniuOssUtil.uploadFile(bucket, object, f.getAbsolutePath(), mimetype, null, null, null);
        String etag = Etag.file(f);
        f.delete();
        FileInfo info = bucketManager.stat(bucket, object);
        assertEquals(etag, info.hash);
        assertEquals(mimetype, info.mimeType);
        qiniuOssUtil.deleteOssObject(bucket, object);
    }


    @Test
    public void testlistObject() {
        String prefix = "liubin";
        ObjectListing l = qiniuOssUtil.listObject(bucket, prefix, null, 5);
        String nextMarker = l.getNextMarker();
        assertNotNull(nextMarker);
        for(String p : l.getCommonPrefixes()) {
            System.out.println(p);
        }
        for(OSSObjectSummary o: l.getObjectSummaries()) {
            System.out.println(o.getKey());
        }
        assertEquals(true, l.isTruncated()); // 还没有获取完，认为是 Truncated

        OSSObjectSummary o = l.getObjectSummaries().get(0);
        assertNotNull(o.getETag());
        assertNotNull(o.getKey());
        assertNotNull(o.getLastModified());
        assertNotNull(o.getSize());

        l = qiniuOssUtil.listObject(bucket, prefix, nextMarker, 5);

        for(OSSObjectSummary os: l.getObjectSummaries()) {
            System.out.println(os.getKey() + ", " + os.getLastModified());
        }

        assertEquals(false, l.isTruncated()); // 已经读取完了，认为 不 是 Truncated
    }




    public static File createFile(int size) throws IOException {
        FileOutputStream fos = null;
        try {
            File f = File.createTempFile("qiniu_oss_" + size + "k", "tmp");
            f.createNewFile();
            fos = new FileOutputStream(f);
            byte[] b = getByte();
            long s = 0;
            while (s < size) {
                int l = (int) Math.min(b.length, size - s);
                fos.write(b, 0, l);
                s += l;
                // 随机生成的文件的每一段(<4M)都不一样
                b = getByte();
            }
            fos.flush();
            return f;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

//    private static Random r = new Random();
    private static byte[] getByte() {
        byte b = (byte) 2; //4; // r.nextInt();
        int len = 498 * 4;
        byte[] bs = new byte[len];

        for (int i = 1; i < len; i++) {
            bs[i] = b;
        }

        bs[10] = (byte) 4; // r.nextInt();
        bs[9] = (byte) 1; // r.nextInt();
        bs[8] = (byte) 4; // r.nextInt();
        bs[7] = (byte) 13; // r.nextInt();
        bs[6] = (byte) 4; // r.nextInt();
        bs[5] = (byte) 4; // r.nextInt();
        bs[4] = (byte) 4; // r.nextInt();
        bs[3] = (byte) 6; // r.nextInt();
        bs[3] = (byte) 24; // r.nextInt();
        bs[1] = (byte) 42; // r.nextInt();
        bs[0] = (byte) 14; // r.nextInt();

        bs[len - 2] = '\r';
        bs[len - 1] = '\n';
        return bs;
    }


}
