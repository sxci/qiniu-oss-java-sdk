package com.aliyun.oss;

import com.aliyun.oss.common.utils.HttpUtil;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.StorageClass;
import com.qiniu.common.Constants;
import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.Auth;
import com.qiniu.util.StringUtils;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.aliyun.oss.internal.OSSConstants.DEFAULT_BUFFER_SIZE;

public class QiniuOssUtil {


    /////////////////////////////////
    /**
     * 直接导入 阿里 同时写的 OssServiceException， 删除下面这个类的定义
     * */
    public static class OssServiceException extends RuntimeException{
        private String code;
        public OssServiceException(String msg, String code) {
            super(msg);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
    /////////////////////////////////


    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private String host;
    private Configuration config;
    private Auth auth;


    public QiniuOssUtil(String accessKey, String secretKey, String host) {
        this(accessKey, secretKey, host, null);
    }

    public QiniuOssUtil(String accessKey, String secretKey, String host, Configuration config) {
        this.host = host;
        this.config = config != null ? config : new Configuration();
        this.auth = Auth.create(accessKey, secretKey);
    }


    public void ossClientShutDown() {
        _bucketManager = null;
        _uploadManager = null;
        _client = null;
    }


    /**
     * 什么都不做，七牛只认 ak sk，额外的 token 没什么意义
     * */
    public void switchOssToken(String token) {
        // do nothing
    }


    /**
     * 没有完整的对应信息，响应 stat 接口：
     * 当前只有: ContentType, ContentLength, LastModified ，若需要其它信息，请提出需求
     * */
    public ObjectMetadata getMeta(String bucket, String object) throws OSSException, ClientException {
        FileInfo info = null;
        try{
            info = getBucketManager().stat(bucket, object);
        } catch (QiniuException e) {
            throwAliException(e);
        }
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(info.mimeType);
        metadata.setContentLength(info.fsize);
        metadata.setLastModified(new Date(info.putTime/10000));

        return metadata;
    }


    /**
     * 七牛要求空间需先创建，不存在空间直接抛异常
     * */
    public boolean isBucketExist(String bucketName) {
        try {
            getBucketManager().getBucketInfo(bucketName);
            return true;
        } catch (QiniuException e) {
            if (e.response != null && e.response.statusCode == 612) { // 612 no such entry
                throw new OssServiceException(" qiniu bucket: "  + bucketName + " is not exist, create it first.", "612");
            } else {
                throwAliException(e);
                return false;
            }
        }
    }


    /**
     * 阿里 是空间不存在，就新建一个， 一个域名可以访问多个空间内容。
     * 七牛 一个域名只能访问一个空间，域名通过构造方法传入，要求空间、域名都已经配置好在启动服务。
     * 即此方法不会被调用到
     * */
    public void createBucket(String bucketName) {
//        try {
//            getBucketManager().createBucket(bucketName, getRegion());
//        } catch (QiniuException e) {
//            throwAliException(e);
//        }
        throw new UnsupportedOperationException("qiniu: create bucket and host first, after that start the server.");
    }


    /**
     * 如果不用新建空间，删除也就不必了
     * */
    public void removeBucket(String bucketName){
//        try {
//            getBucketManager().deleteBucket(bucketName);
//        } catch (QiniuException e) {
//            throwAliException(e);
//        }
        throw new UnsupportedOperationException("qiniu: do not need remove bucket.");
    }


    public void fetchFile(String bucket, String object, String destPath) throws OssServiceException {
        int statusCode = 0;
        Response res = null;
        try{
            File file = new File(destPath);
            String url = genUrlWithToken(bucket, object);
            res = fetchUrl(url);
            statusCode = res.code();
            filterOk(res, url);
            long contentLength = res.body().contentLength();
            saveToFile(res.body().byteStream(), file);
            log.info("OssBucket: " + bucket + ", ossObject: " + object + ", destPath: " + destPath
                    + ", size: " + contentLength);
        } catch (IOException e) {
            throw new OssServiceException("OssBucket: " + bucket + ", ossObject: "  + object +
                    "ErrorCode: " + statusCode + ", " + e.getMessage(), statusCode+"");
        } finally {
            if (res != null) {
                res.close();
            }
        }
    }


    public String fetchContent(String bucket, String object) throws OssServiceException {
        int statusCode = 0;
        Response res = null;
        try{
            String ret = "";
            long contentLength = 0;

            String url = genUrlWithToken(bucket, object);
            res = fetchUrl(url);
            statusCode = res.code();
            if (statusCode == 404 && !StringUtils.isNullOrEmpty(res.header("X-Reqid"))) {
                // do nothing
            } else {
                filterOk(res, url);
                contentLength = res.body().contentLength();
                ret = res.body().string();
            }
            log.info("OssBucket: " + bucket + ", ossObject: " + object
                    + ", size: " + contentLength);
            return ret;
        } catch (IOException e) {
            throw new OssServiceException("OssBucket: " + bucket + ", ossObject: " + object +
                    "ErrorCode: " + statusCode + ", " + e.getMessage(), statusCode+"");
        } finally {
            res.close();
        }
    }


    /**
     * 内容不大，几兆，直接使用 字节数组上传
     * */
    public String uploadContent(String bucket, String object, String content, String contentType, Date expire,
                                Integer maxAge) throws OssServiceException {
        String token = auth.uploadToken(bucket);
        com.qiniu.http.Response ret = null;
        try {
            ret = getUploadManager().put(content.getBytes(), object, token, null, contentType, true);
            if (ret.isOK()) {
                // key, object are equal
                Object key = ret.jsonToMap().get("key");
                if (key == null) {
                    key = object;
                }
                return genUrl(bucket, object);
            }
            throw new OssServiceException(ret.toString(), ret.statusCode + "");
        } catch (QiniuException e0) {
            try {
                throwAliException(e0);
            } catch (OSSException e) {
                throw new OssServiceException("OssBucket: " + bucket + ", ossObject: " + object +
                        ", errorCode: " + e.getErrorCode()  + ", " + e.getMessage(), e.getErrorCode());
            } catch (ClientException e) {
                throw new OssServiceException("OssBucket: " + bucket + ", ossObject: " + object +
                        ", errorCode: " + e.getErrorCode() + ", "  + e.getMessage(), e.getErrorCode());
            }
        } finally {
            if (ret != null) {
                ret.close();
            }
        }
        return null;
    }


    public void uploadFile(String bucket, String object, String filePath, String contentType,
                           Map<String, String> userMeta, Date expire, Integer maxAge) throws OssServiceException {
        String token = auth.uploadToken(bucket);
        com.qiniu.http.Response ret = null;
        try {
            ret = getUploadManager().put(filePath, object, token, null, contentType, true);
            if (!ret.isOK()) {
                throw new OssServiceException(ret.toString(), ret.statusCode + "");
            }
        } catch (QiniuException e0) {
            try {
                throwAliException(e0);
            } catch (OSSException e) {
                throw new OssServiceException("OssBucket: " + bucket + ", ossObject: " + object +
                        ", errorCode: " + e.getErrorCode() + ", " + e.getMessage(), e.getErrorCode());
            } catch (ClientException e) {
                throw new OssServiceException("OssBucket: " + bucket + ", ossObject: " + object +
                        ", errorCode: " + e.getErrorCode() + ", " + e.getMessage(), e.getErrorCode());
            }
        } finally {
            if (ret != null) {
                ret.close();
            }
        }
    }


    public void deleteOssObject(String bucket, String object) throws OssServiceException {
        try {
            getBucketManager().delete(bucket, object);
        } catch (QiniuException e0) {
            try {
                throwAliException(e0);
            } catch (OSSException e) {
                throw new OssServiceException("OssBucket: " + bucket + " ossObject: " + object +
                        ", errorCode: " + e.getErrorCode() + ", " + e.getMessage(), e.getErrorCode());
            } catch (ClientException e) {
                throw new OssServiceException("OssBucket: " + bucket + " ossObject: " + object +
                        ", errorCode: " + e.getErrorCode() + ", " + e.getMessage(), e.getErrorCode());
            }
        }
    }


    public ObjectListing listObject(String bucket, String prefix, String marker, Integer maxKeys) throws OssServiceException {
        try{
            int max = maxKeys == null ? 200 : maxKeys.intValue();
            FileListing files = getBucketManager().listFiles(bucket, prefix,
                    marker, max, null);
            ObjectListing objs = new ObjectListing();

            objs.setBucketName(bucket);
            objs.setPrefix(prefix);
            objs.setMarker(marker);
            objs.setMaxKeys(max);

            objs.setTruncated(!files.isEOF());
            objs.setNextMarker(files.marker);
            if (files.commonPrefixes != null) {
                objs.setCommonPrefixes(Arrays.asList(files.commonPrefixes));
            }

            List<OSSObjectSummary> objectSummaries = new ArrayList<OSSObjectSummary>(files.items.length);
            for (int i = 0; i < files.items.length; i++) {
                FileInfo info = files.items[i];
                OSSObjectSummary obj = new OSSObjectSummary();
                // 0 表示标准存储；1 表示低频存储
                StorageClass storageClass = info.type == 0 ? StorageClass.Standard : StorageClass.IA;
                obj.setStorageClass(storageClass.toString());
                obj.setSize(info.fsize);
                obj.setLastModified(new Date(info.putTime / 10000)); // 百纳秒， 4 个 0 到 毫秒
                obj.setKey(info.key);
                obj.setETag(info.hash);
                // 0 公开  1 私有
                //TODO no owner
                objectSummaries.add(i, obj);
            }
            objs.setObjectSummaries(objectSummaries);

            return objs;
        } catch (QiniuException e0) {
            try {
                throwAliException(e0);
            } catch (OSSException e) {
                throw new OssServiceException("OssBucket: " + bucket + ", marker: " + marker +
                        ", errorCode: " + e.getErrorCode() + e.getMessage(), e.getErrorCode());
            } catch (ClientException e) {
                throw new OssServiceException("OssBucket: " + bucket + ", marker: " + marker +
                                ", errorCode: " + e.getErrorCode() + ", " + e.getMessage(), e.getErrorCode());
            }
            return null;
        }
    }


    private okhttp3.Response fetchUrl(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", userAgent())
                .tag(new IpTag())
                .build();
        Response res = getClient().newCall(request).execute();
        return res;
    }


    private okhttp3.Response filterOk(okhttp3.Response res, String url) throws IOException {
        if (!res.isSuccessful()) {
            byte[] b = res.body().source().readByteArray(Math.min(512, res.body().contentLength()));
            String rawResponseError = new String(b);
            IpTag ipTag = (IpTag)res.request().tag();
            String ip = ipTag != null ? ipTag.ip : "unkown";
            String msg = String.format("%d %s, remoteIp:%s, reqId:%s, xlog:%s, adress:%s, error:%s",
                    res.code(), res.message(), ip, res.header("X-Reqid"), res.header("X-Log"),
                    url, rawResponseError);
            throw new IOException(msg);
        }
        return res;
    }


    private void saveToFile(InputStream is, File file) throws IOException {
        OutputStream os = null;
        try {
            os = new BufferedOutputStream(new FileOutputStream(file));
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }



    /** 实际使用中，bucket 和 host 一直不变，不需要动态去获取不同空间的不同域名， */
    private String genUrl(String bucket, String object) {
        return getHost() + "/" + HttpUtil.urlEncode(object, "UTF-8");
    }

    private String genUrlWithToken(String bucket, String object) {
        String ourl = genUrl(bucket, object);
        String url = auth.privateDownloadUrl(ourl);
        return url;
    }

    private String getHost() {
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        } else {
            return "http://" + host;
        }
    }

    private void throwAliException(QiniuException e) throws OSSException, ClientException {
        //TODO
        if (e.code() > -1) {
            throw new OSSException(e.getMessage(), e.code() + "", null, null, null, null, null, e);
        }
        throw new ClientException(e.getMessage(), e.code() + "", null, e);
    }


    private String getRegion() {
        return config.zone.getRegion();
    }


    private static String defaultAgent;
    public static String userAgent() {
        if (defaultAgent == null) {
            String javaVersion = "Java/" + System.getProperty("java.version");
            String os = System.getProperty("os.name") + " "
                    + System.getProperty("os.arch") + " " + System.getProperty("os.version");
            String sdk = "QiniuOss/" + Constants.VERSION;
            defaultAgent = sdk + " (" + os + ") " + javaVersion;
        }
        return defaultAgent;
    }


    private BucketManager _bucketManager;

    private BucketManager getBucketManager() {
        if (_bucketManager == null) {
            synchronized (config) {
                if (_bucketManager == null) {
                    _bucketManager = new BucketManager(auth, config);
                }
            }
        }
        return _bucketManager;
    }

    private UploadManager _uploadManager;
    private UploadManager getUploadManager() {
        if (_uploadManager == null) {
            synchronized (config) {
                if (_uploadManager == null) {
                    _uploadManager = new UploadManager(config);
                }
            }
        }
        return _uploadManager;
    }

    private OkHttpClient _client;

    private OkHttpClient getClient() {
        // almost copy from com.qiniu.http.Client
        if (_client == null) {
            synchronized (config) {
                if (_client == null) {
                    Dispatcher dispatcher = new Dispatcher();
                    dispatcher.setMaxRequests(config.dispatcherMaxRequests);
                    dispatcher.setMaxRequestsPerHost(config.dispatcherMaxRequestsPerHost);
                    ConnectionPool connectionPool = new ConnectionPool(config.connectionPoolMaxIdleCount,
                            config.connectionPoolMaxIdleMinutes, TimeUnit.MINUTES);
                    OkHttpClient.Builder builder = new OkHttpClient.Builder();

                    builder.dispatcher(dispatcher);
                    builder.connectionPool(connectionPool);
                    builder.addNetworkInterceptor(new Interceptor() {
                        @Override
                        public okhttp3.Response intercept(Chain chain) throws IOException {
                            Request request = chain.request();
                            okhttp3.Response response = chain.proceed(request);
                            try {
                                IpTag tag = (IpTag) request.tag();
                                tag.ip = chain.connection().socket().getRemoteSocketAddress().toString();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            return response;
                        }
                    });
                    if (config.dns != null) {
                        builder.dns(new okhttp3.Dns() {
                            @Override
                            public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                                try {
                                    return config.dns.lookup(hostname);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                return okhttp3.Dns.SYSTEM.lookup(hostname);
                            }
                        });
                    }
                    if (config.proxy != null) {
                        Proxy proxy = new Proxy(config.proxy.type, new InetSocketAddress(config.proxy.hostAddress, config.proxy.port));
                        builder.proxy(proxy);
                        if (config.proxy.user != null && config.proxy.password != null) {
                            Authenticator authenticator = new Authenticator() {
                                @Override
                                public okhttp3.Request authenticate(Route route, okhttp3.Response response) throws IOException {
                                    String credential = okhttp3.Credentials.basic(config.proxy.user, config.proxy.password);
                                    return response.request().newBuilder().
                                            header("Proxy-Authorization", credential).
                                            header("Proxy-Connection", "Keep-Alive").build();
                                }
                            };
                            builder.proxyAuthenticator(authenticator);
                        }
                    }
                    builder.connectTimeout(config.connectTimeout, TimeUnit.SECONDS);
                    builder.readTimeout(config.readTimeout, TimeUnit.SECONDS);
                    builder.writeTimeout(config.writeTimeout, TimeUnit.SECONDS);
                    _client = builder.build();
                }
            }
        }
        return _client;
    }

    public static class IpTag {
        public String ip = "";
    }

}


