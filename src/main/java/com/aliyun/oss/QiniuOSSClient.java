/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.oss;

import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.comm.ResponseMessage;
import com.aliyun.oss.common.utils.HttpUtil;
import com.aliyun.oss.common.utils.VersionInfoUtils;
import com.aliyun.oss.model.*;
import com.aliyun.oss.model.SetBucketCORSRequest.CORSRule;
import com.qiniu.common.AutoZone;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.AclType;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.storage.model.IndexPageType;
import com.qiniu.util.Auth;
import com.qiniu.util.StringUtils;
import okhttp3.Authenticator;
import okhttp3.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.aliyun.oss.common.utils.IOUtils.safeClose;
import static com.aliyun.oss.common.utils.LogUtils.logException;
import static com.aliyun.oss.internal.OSSConstants.DEFAULT_BUFFER_SIZE;
import static com.aliyun.oss.internal.OSSUtils.OSS_RESOURCE_MANAGER;

// 若部分代码要求传入 OSSClient ，可修改为 public class QiniuOSSClient extends OSSClient 以满足语法要求

/**
 * The entry point class of OSS that implements the OSS interface.
 */
public class QiniuOSSClient implements OSS {

    private CredentialsProvider credsProvider;

    private static String unsupportedMsg = "QiniuOSSClient do not support all oss's method";

    private Auth auth;
    private Configuration config;
    private BucketManager _bucketManager;
    private UploadManager _uploadManager;

    public QiniuOSSClient(String accessKeyId, String secretAccessKey, Configuration config) {
        if (config.zone == null || config.zone instanceof AutoZone ||
                config.zone.getRegion() == null || config.zone.getRegion().length() == 0) {
            throw new IllegalArgumentException("region must be set-up or not a fix zone");
        }
        this.auth = Auth.create(accessKeyId, secretAccessKey);
        this.config = config;
    }


    private String getRegion() {
        return config.zone.getRegion();
    }

    private void throwAliException(QiniuException e) {
        //TODO
        if (e.code() > -1) {
            throw new OSSException(e.getMessage());
        }
        throw new ClientException(e.getMessage());
    }

    @Override
    public Bucket createBucket(String bucketName) throws OSSException, ClientException {
        CreateBucketRequest req = new CreateBucketRequest(bucketName);
        req.setStorageClass(StorageClass.Standard);
        req.setCannedACL(CannedAccessControlList.PublicReadWrite);
        return createBucket(req);
    }

    @Override
    public Bucket createBucket(CreateBucketRequest createBucketRequest) throws OSSException, ClientException {
        String bucketName = createBucketRequest.getBucketName();
        StorageClass ctype = createBucketRequest.getStorageClass();
        try {
            String region = getRegion();
            // TODO 不支持创建时指定是否低频，是否私有
            // https://developer.qiniu.com/kodo/api/1382/mkbucketv2
            getBucketManager().createBucket(bucketName, region);
            Bucket bkt = new Bucket(bucketName);
            bkt.setLocation(region);
            bkt.setCreationDate(new Date());
            // 设置 私有
            if (createBucketRequest.getCannedACL() == CannedAccessControlList.Private) {
                setBucketAcl(bucketName, CannedAccessControlList.Private);
            }
            // TODO 设置 低频存储
//            if (ctype == StorageClass.IA || ctype == StorageClass.Archive) {
//                getBucketManager()
//            }
            // no owner
            return bkt;
        } catch (QiniuException e) {
            throwAliException(e);
            return null;
        }
    }

    @Override
    public void deleteBucket(String bucketName) throws OSSException, ClientException {
        try {
            if (isEmptyBucket(bucketName)) {
                getBucketManager().deleteBucket(bucketName);
            } else {
                throw new OSSException("BucketNotEmpty: " + bucketName);
            }
        } catch (QiniuException e) {
            if (e.code() == 612) {
                throw new OSSException("NoSuchBucket: " + bucketName);
            }
            throwAliException(e);
        }
    }

    public boolean isEmptyBucket(String bucketName) throws OSSException, ClientException {
        ObjectListing objs = listObjects(new ListObjectsRequest(bucketName, null, null, null, 2));
        return objs.getObjectSummaries().size() == 0;
    }

    @Override
    public void deleteBucket(GenericRequest genericRequest) throws OSSException, ClientException {
        deleteBucket(genericRequest.getBucketName());
    }

    @Override
    public List<Bucket> listBuckets() throws OSSException, ClientException {
        try {
            // TODO 七牛接口只包含空间名
            // https://developer.qiniu.com/kodo/api/3926/get-service
            // 若需要额外信息，可考虑实现接口：
            // CHECKSTYLE:OFF
            // https://github.com/qbox/product/blob/master/kodo/bucket/uc.md#v2bucketinfos-%E8%8E%B7%E5%8F%96%E7%94%A8%E6%88%B7%E6%8C%87%E5%AE%9A-zone-%E7%9A%84-bucket-%E4%BF%A1%E6%81%AF%E5%88%97%E8%A1%A8
            // CHECKSTYLE:ON
            String[] bkts = getBucketManager().buckets();
            List<Bucket> list = new ArrayList<Bucket>(bkts.length);
            for (int i = 0; i < bkts.length; i++) {
                String name = bkts[i];
                Bucket bkt = new Bucket(name);
                //TODO no owner
                list.add(i, bkt);
            }
            return list;
        } catch (QiniuException e) {
            throwAliException(e);
        }
        return null;
    }

    @Override
    public BucketList listBuckets(ListBucketsRequest listBucketsRequest) throws OSSException, ClientException {
        // 没有直接接口，若确实需要，可以在客户端实现过滤
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketList listBuckets(String prefix, String marker, Integer maxKeys) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public void deleteObject(String bucketName, String key) throws OSSException, ClientException {
        try {
            getBucketManager().delete(bucketName, key);
        } catch (QiniuException e) {
            throwAliException(e);
        }
    }

    @Override
    public void deleteObject(GenericRequest genericRequest) throws OSSException, ClientException {
        deleteObject(genericRequest.getBucketName(), genericRequest.getKey());
    }

    @Override
    public DeleteObjectsResult deleteObjects(DeleteObjectsRequest req)
            throws OSSException, ClientException {
        List<String> keys = req.getKeys();
        if (keys.size() == 0) {
            return new DeleteObjectsResult();
        }
        BucketManager.BatchOperations opt = new BucketManager.BatchOperations();
        for (String key : keys) {
            opt.addDeleteOp(req.getBucketName(), key);
        }
        try {
            Response res = getBucketManager().batch(opt);
            DeleteObjectsResult ret = new DeleteObjectsResult();
            // TODO 组装响应结果 // 不要求实现，只实现了删除功能，没有组装响应结果
            return ret;
        } catch (QiniuException e) {
            throwAliException(e);
            return null;
        }
//        // 不要求实现，需要的话，可以考虑 batch 删除
//        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public PutObjectResult putObject(String bucketName, String key, InputStream input)
            throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public PutObjectResult putObject(String bucketName, String key, InputStream input, ObjectMetadata metadata)
            throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public PutObjectResult putObject(String bucketName, String key, File file, ObjectMetadata metadata)
            throws OSSException, ClientException {
        String token = auth.uploadToken(bucketName, key);
        //TODO 先忽略 metadata ，还不知道怎么用
        try {
            Response res = getUploadManager().put(file, key, token, null, null, true);

            PutObjectResult objres = new PutObjectResult();
            Map<String, String> putRet = res.jsonToObject(Map.class);
            objres.setETag(putRet.get("hash")); //TODO 是 文件 hash ，还是响应头信息
            objres.setCallbackResponseBody(res.bodyStream());
            objres.setRequestId(res.reqId);
//            objres.setServerCRC();
//            objres.setClientCRC();
//            objres.setResponse(); //TODO 不设置会有什么影响

            return objres;
        } catch (QiniuException e) {
            throwAliException(e);
        }
        return null;
    }

    @Override
    public PutObjectResult putObject(String bucketName, String key, File file)
            throws OSSException, ClientException {
        return putObject(bucketName, key, file, null);
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest putObjectRequest)
            throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public PutObjectResult putObject(URL signedUrl, String filePath, Map<String, String> requestHeaders)
            throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public PutObjectResult putObject(URL signedUrl, String filePath, Map<String, String> requestHeaders,
                                     boolean useChunkEncoding) throws OSSException, ClientException {
        // 不要求实现，暂不清楚行为逻辑
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public PutObjectResult putObject(URL signedUrl, InputStream requestContent, long contentLength,
                                     Map<String, String> requestHeaders) throws OSSException, ClientException {
        // 不要求实现，暂不清楚行为逻辑
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public PutObjectResult putObject(URL signedUrl, InputStream requestContent, long contentLength,
                                     Map<String, String> requestHeaders, boolean useChunkEncoding) throws OSSException, ClientException {
        // 不要求实现，暂不清楚行为逻辑
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public OSSObject getObject(String bucketName, String key) throws OSSException, ClientException {
        String domain = getHttpDomain(bucketName);

        String url = "http://" + domain + "/" + HttpUtil.urlEncode(key, "UTF-8");
        String signedUrl = auth.privateDownloadUrl(url, 3600 * 3);

        String iovipHttp = config.zone.getIovipHttp(null);
        int s = iovipHttp.indexOf("://");
        s = s == -1 ? 0 : s + 3;
        String iovipHttpHost = iovipHttp.substring(s);
        signedUrl = signedUrl.replaceFirst(domain, iovipHttpHost);

        Request request = new Request.Builder()
                .url(signedUrl)
                .addHeader("Host", domain)
                .addHeader("User-Agent", VersionInfoUtils.getDefaultUserAgent())
                .build();

        try {
            okhttp3.Response res = getClient().newCall(request).execute();

            if (!res.isSuccessful()) {
                byte[] b = res.body().source().readByteArray(Math.min(512, res.body().contentLength()));
                String rawResponseError = new String(b);
                res.close();

                if (res.code() == 404 && rawResponseError.indexOf("\"no such domain\"") != -1) {
                    removeHttpDomainCache(bucketName);
                }

                throw new OSSException(res.message(), res.code() + "", res.header("X-Reqid"),
                        domain, null, null, "GET", rawResponseError);
            }
            OSSObject obj = new OSSObject();
            obj.setBucketName(bucketName);
            obj.setKey(key);
            obj.setObjectMetadata(new ObjectMetadata()); // TODO ObjectMetadata  怎么获取？应该是什么数据
            obj.setObjectContent(res.body().byteStream());

            return obj;
        } catch (IOException e) {
            throw new OSSException(e.getMessage(), e);
        }
//        return null;
    }

    @Override
    public ObjectMetadata getObject(GetObjectRequest getObjectRequest, File file)
            throws OSSException, ClientException {
        OSSObject ossObject = getObject(getObjectRequest.getBucketName(), getObjectRequest.getKey());

        OutputStream outputStream = null;
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = ossObject.getObjectContent().read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

            // TODO 没有校验 etag
            return ossObject.getObjectMetadata();
        } catch (IOException ex) {
            logException("Cannot read object content stream: ", ex);
            throw new ClientException(OSS_RESOURCE_MANAGER.getString("CannotReadContentStream"), ex);
        } finally {
            safeClose(outputStream);
            safeClose(ossObject.getObjectContent());
        }
    }

    @Override
    public OSSObject getObject(GetObjectRequest getObjectRequest) throws OSSException, ClientException {
        return getObject(getObjectRequest.getBucketName(), getObjectRequest.getKey());
    }

    @Override
    public OSSObject getObject(URL signedUrl, Map<String, String> requestHeaders) throws OSSException, ClientException {
        GetObjectRequest getObjectRequest = new GetObjectRequest(signedUrl, requestHeaders);
        // 不要求实现，暂不清楚行为逻辑
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public ObjectListing listObjects(String bucketName) throws OSSException, ClientException {
        return listObjects(bucketName, null);
    }

    @Override
    public ObjectListing listObjects(String bucketName, String prefix) throws OSSException, ClientException {
        return listObjects(new ListObjectsRequest(bucketName, prefix, null, null, null));
    }

    @Override
    public ObjectListing listObjects(ListObjectsRequest p) throws OSSException, ClientException {
        try {
            int max = p.getMaxKeys() == null ? 0 : p.getMaxKeys().intValue();
            FileListing files = getBucketManager().listFiles(p.getBucketName(), p.getPrefix(),
                    p.getMarker(), max, p.getDelimiter());
            ObjectListing objs = new ObjectListing();

            objs.setBucketName(p.getBucketName());
            objs.setPrefix(p.getPrefix());
            objs.setMarker(p.getMarker());
            objs.setMaxKeys(max);
            objs.setDelimiter(p.getDelimiter());

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
                obj.setLastModified(new Date(info.putTime / 10000000)); // 百纳秒， 7 个 0
                obj.setKey(info.key);
                obj.setETag(info.hash);
                // 0 公开  1 私有
                //TODO no owner
                objectSummaries.add(i, obj);
            }
            objs.setObjectSummaries(objectSummaries);

            return objs;
        } catch (QiniuException e) {
            throwAliException(e);
            return null;
        }
    }


    @Override
    public URL generatePresignedUrl(String bucketName, String key, Date expiration) throws ClientException {
        String domain = "";
        try {
            String[] domains = getBucketManager().domainList(bucketName);
            domain = domains[0];
        } catch (QiniuException e) {
            throwAliException(e);
        } catch (Exception e) {
            throw new ClientException("no domain on the bucket " + bucketName, e);
        }
        String url = "http://" + domain + "/" + HttpUtil.urlEncode(key, "UTF-8");
        String signedUrl = auth.privateDownloadUrlWithDeadline(url, expiration.getTime() / 1000);
        try {
            return new URL(signedUrl);
        } catch (MalformedURLException e) {
            throw new ClientException(e);
        }
    }

    @Override
    public URL generatePresignedUrl(String bucketName, String key, Date expiration, HttpMethod method)
            throws ClientException {
        return generatePresignedUrl(bucketName, key, expiration);
    }

    @Override
    public URL generatePresignedUrl(GeneratePresignedUrlRequest request) throws ClientException {
        return generatePresignedUrl(request.getBucketName(), request.getKey(), request.getExpiration());
    }


    @Override
    public void setBucketAcl(String bucketName, CannedAccessControlList cannedACL)
            throws OSSException, ClientException {
        AclType acl = AclType.PUBLIC;
        if (cannedACL == CannedAccessControlList.Private) {
            acl = AclType.PRIVATE;
        }
        try {
            getBucketManager().setBucketAcl(bucketName, acl);
        } catch (QiniuException e) {
            throwAliException(e);
        }
    }

    @Override
    public void setBucketAcl(SetBucketAclRequest request) throws OSSException, ClientException {
        setBucketAcl(request.getBucketName(), request.getCannedACL());
    }

    @Override
    public AccessControlList getBucketAcl(String bucketName) throws OSSException, ClientException {
        BucketInfo info = getBucketInfo(bucketName);
        AccessControlList al = new AccessControlList();
        al.setCannedACL(info.getCannedACL());
        return al;
    }

    @Override
    public AccessControlList getBucketAcl(GenericRequest genericRequest) throws OSSException, ClientException {
        return getBucketAcl(genericRequest.getBucketName());
    }


    @Override
    public BucketInfo getBucketInfo(String bucketName) throws OSSException, ClientException {
        try {
            com.qiniu.storage.model.BucketInfo qinfo = getBucketManager().getBucketInfo(bucketName);
            BucketInfo info = new BucketInfo();
            Bucket bkt = new Bucket(bucketName);
            bkt.setLocation(qinfo.getRegion());
            info.setBucket(bkt);
            info.setCannedACL(qinfo.getPrivate() == 1 ? CannedAccessControlList.Private : CannedAccessControlList.PublicReadWrite);
            return info;
        } catch (QiniuException e) {
            throwAliException(e);
            return null;
        }
    }

    @Override
    public BucketInfo getBucketInfo(GenericRequest genericRequest) throws OSSException, ClientException {
        return getBucketInfo(genericRequest.getBucketName());
    }


    @Override
    public boolean doesBucketExist(String bucketName) throws OSSException, ClientException {
        try {
            getBucketManager().getBucketInfo(bucketName);
            return true;
        } catch (QiniuException e) {
            if (e.response != null && e.response.statusCode == 612) { // 612 no such entry
                return false;
            } else {
                throwAliException(e);
                return false;
            }
        }
    }

    @Override
    public boolean doesBucketExist(GenericRequest genericRequest) throws OSSException, ClientException {
        return this.doesBucketExist(genericRequest.getBucketName());
    }

    /**
     * Deprecated. Please use {@link QiniuOSSClient#doesBucketExist(String)} instead.
     */
    @Deprecated
    public boolean isBucketExist(String bucketName) throws OSSException, ClientException {
        return this.doesBucketExist(bucketName);
    }


    @Override
    public void setBucketWebsite(SetBucketWebsiteRequest request) throws OSSException, ClientException {
        if (StringUtils.isNullOrEmpty(request.getIndexDocument()) &&
                StringUtils.isNullOrEmpty(request.getErrorDocument())) {
            try {
                getBucketManager().setIndexPage(request.getBucketName(), IndexPageType.NO);
            } catch (QiniuException e) {
                throwAliException(e);
            }
            return;
        }
        if (!"index.html".equals(request.getIndexDocument()) || !"error-404".equals(request.getErrorDocument())) {
            throw new ClientException("indexDocument must be index.html or '', errorDocument must be error-404 or ''");
        }
        try {
            getBucketManager().setIndexPage(request.getBucketName(), IndexPageType.HAS);
        } catch (QiniuException e) {
            throwAliException(e);
        }
    }

    @Override
    public BucketWebsiteResult getBucketWebsite(String bucketName) throws OSSException, ClientException {
        try {
            com.qiniu.storage.model.BucketInfo qinfo = getBucketManager().getBucketInfo(bucketName);
            BucketWebsiteResult ret = new BucketWebsiteResult();
            if (qinfo.getNoIndexPage() == IndexPageType.HAS.getType()) {
                ret.setErrorDocument("error-404");
                ret.setIndexDocument("index.html");
            }
            return ret;
        } catch (QiniuException e) {
            throwAliException(e);
            return null;
        }
    }

    @Override
    public BucketWebsiteResult getBucketWebsite(GenericRequest request) throws OSSException, ClientException {
        return getBucketWebsite(request.getBucketName());
    }

    @Override
    public void deleteBucketWebsite(String bucketName) throws OSSException, ClientException {
        try {
            getBucketManager().setIndexPage(bucketName, IndexPageType.NO);
        } catch (QiniuException e) {
            throwAliException(e);
        }
    }

    @Override
    public void deleteBucketWebsite(GenericRequest genericRequest) throws OSSException, ClientException {
        deleteBucketWebsite(genericRequest.getBucketName());
    }


    ///////////////////////////////////

    @Override
    public BucketMetadata getBucketMetadata(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketMetadata getBucketMetadata(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketReferer(String bucketName, BucketReferer referer) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketReferer(SetBucketRefererRequest setBucketRefererRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketReferer getBucketReferer(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketReferer getBucketReferer(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public String getBucketLocation(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public String getBucketLocation(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public OSSObject selectObject(SelectObjectRequest selectObjectRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public CopyObjectResult copyObject(String sourceBucketName, String sourceKey, String destinationBucketName,
                                       String destinationKey) throws OSSException, ClientException {
//        getBucketManager().copy(sourceBucketName, sourceKey, destinationBucketName, destinationKey);
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public CopyObjectResult copyObject(CopyObjectRequest copyObjectRequest) throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public SimplifiedObjectMeta getSimplifiedObjectMeta(String bucketName, String key)
            throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public SimplifiedObjectMeta getSimplifiedObjectMeta(GenericRequest genericRequest)
            throws OSSException, ClientException {
//        try {
//            FileInfo info = getBucketManager().stat(genericRequest.getBucketName(), genericRequest.getKey());
//            SimplifiedObjectMeta meta = new SimplifiedObjectMeta();
//            meta.setETag();
//        } catch (QiniuException e) {
//            throwAliException(e);
//        }
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public ObjectMetadata getObjectMetadata(String bucketName, String key) throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public ObjectMetadata getObjectMetadata(GenericRequest genericRequest) throws OSSException, ClientException {
        // 不要求实现
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public SelectObjectMetadata createSelectObjectMetadata(CreateSelectObjectMetadataRequest createSelectObjectMetadataRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public AppendObjectResult appendObject(AppendObjectRequest appendObjectRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public boolean doesObjectExist(String bucketName, String key) throws OSSException, ClientException {
        // 不要求实现 stat
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public boolean doesObjectExist(String bucketName, String key, boolean isOnlyInOSS) {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Deprecated
    @Override
    public boolean doesObjectExist(HeadObjectRequest headObjectRequest) throws OSSException, ClientException {
        // 不要求实现 batch
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public boolean doesObjectExist(GenericRequest genericRequest) throws OSSException, ClientException {
        // 不要求实现 batch
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setObjectAcl(String bucketName, String key, CannedAccessControlList cannedACL)
            throws OSSException, ClientException {
        // 不要求实现 无文档
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setObjectAcl(SetObjectAclRequest setObjectAclRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public ObjectAcl getObjectAcl(String bucketName, String key) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public ObjectAcl getObjectAcl(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public RestoreObjectResult restoreObject(String bucketName, String key) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public RestoreObjectResult restoreObject(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    @Override
    public void abortMultipartUpload(AbortMultipartUploadRequest request) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public CompleteMultipartUploadResult completeMultipartUpload(CompleteMultipartUploadRequest request)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public MultipartUploadListing listMultipartUploads(ListMultipartUploadsRequest request)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public PartListing listParts(ListPartsRequest request) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UploadPartResult uploadPart(UploadPartRequest request) throws OSSException, ClientException {
        // 分片上传？
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UploadPartCopyResult uploadPartCopy(UploadPartCopyRequest request) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketCORS(SetBucketCORSRequest request) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<CORSRule> getBucketCORSRules(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<CORSRule> getBucketCORSRules(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketCORSRules(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketCORSRules(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public ResponseMessage optionsObject(OptionsRequest request) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketLogging(SetBucketLoggingRequest request) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketLoggingResult getBucketLogging(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketLoggingResult getBucketLogging(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketLogging(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketLogging(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void putBucketImage(PutBucketImageRequest request) throws OSSException, ClientException {
        // 原文件保护 , style ？
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public GetBucketImageResult getBucketImage(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public GetBucketImageResult getBucketImage(String bucketName, GenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketImage(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketImage(String bucketName, GenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void putImageStyle(PutImageStyleRequest putImageStyleRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteImageStyle(String bucketName, String styleName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteImageStyle(String bucketName, String styleName, GenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public GetImageStyleResult getImageStyle(String bucketName, String styleName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public GetImageStyleResult getImageStyle(String bucketName, String styleName, GenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<Style> listImageStyle(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<Style> listImageStyle(String bucketName, GenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketProcess(SetBucketProcessRequest setBucketProcessRequest) throws OSSException, ClientException {
        // 异步处理？
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketProcess getBucketProcess(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketProcess getBucketProcess(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public String generatePostPolicy(Date expiration, PolicyConditions conds) {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public String calculatePostSignature(String postPolicy) throws ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketLifecycle(SetBucketLifecycleRequest setBucketLifecycleRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<LifecycleRule> getBucketLifecycle(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<LifecycleRule> getBucketLifecycle(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketLifecycle(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketLifecycle(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketTagging(String bucketName, Map<String, String> tags) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketTagging(String bucketName, TagSet tagSet) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketTagging(SetBucketTaggingRequest setBucketTaggingRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public TagSet getBucketTagging(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public TagSet getBucketTagging(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketTagging(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketTagging(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void addBucketReplication(AddBucketReplicationRequest addBucketReplicationRequest)
            throws OSSException, ClientException {
        // 跨区域同步？
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<ReplicationRule> getBucketReplication(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<ReplicationRule> getBucketReplication(GenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketReplication(String bucketName, String replicationRuleID)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketReplication(DeleteBucketReplicationRequest deleteBucketReplicationRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketReplicationProgress getBucketReplicationProgress(String bucketName, String replicationRuleID)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketReplicationProgress getBucketReplicationProgress(
            GetBucketReplicationProgressRequest getBucketReplicationProgressRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<String> getBucketReplicationLocation(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<String> getBucketReplicationLocation(GenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void addBucketCname(AddBucketCnameRequest addBucketCnameRequest) throws OSSException, ClientException {
        // 帮订域名？
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<CnameConfiguration> getBucketCname(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<CnameConfiguration> getBucketCname(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketCname(String bucketName, String domain) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteBucketCname(DeleteBucketCnameRequest deleteBucketCnameRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketStat getBucketStat(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public BucketStat getBucketStat(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketStorageCapacity(String bucketName, UserQos userQos) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setBucketStorageCapacity(SetBucketStorageCapacityRequest setBucketStorageCapacityRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UserQos getBucketStorageCapacity(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UserQos getBucketStorageCapacity(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UploadFileResult uploadFile(UploadFileRequest uploadFileRequest) throws Throwable {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public DownloadFileResult downloadFile(DownloadFileRequest downloadFileRequest) throws Throwable {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public CreateLiveChannelResult createLiveChannel(CreateLiveChannelRequest createLiveChannelRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setLiveChannelStatus(String bucketName, String liveChannel, LiveChannelStatus status)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void setLiveChannelStatus(SetLiveChannelRequest setLiveChannelRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public LiveChannelInfo getLiveChannelInfo(String bucketName, String liveChannel)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public LiveChannelInfo getLiveChannelInfo(LiveChannelGenericRequest liveChannelGenericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public LiveChannelStat getLiveChannelStat(String bucketName, String liveChannel)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public LiveChannelStat getLiveChannelStat(LiveChannelGenericRequest liveChannelGenericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteLiveChannel(String bucketName, String liveChannel) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteLiveChannel(LiveChannelGenericRequest liveChannelGenericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<LiveChannel> listLiveChannels(String bucketName) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public LiveChannelListing listLiveChannels(ListLiveChannelsRequest listLiveChannelRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<LiveRecord> getLiveChannelHistory(String bucketName, String liveChannel)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<LiveRecord> getLiveChannelHistory(LiveChannelGenericRequest liveChannelGenericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void generateVodPlaylist(String bucketName, String liveChannelName, String PlaylistName, long startTime,
                                    long endTime) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void generateVodPlaylist(GenerateVodPlaylistRequest generateVodPlaylistRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public String generateRtmpUri(String bucketName, String liveChannelName, String PlaylistName, long expires)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public String generateRtmpUri(GenerateRtmpUriRequest generateRtmpUriRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void createSymlink(String bucketName, String symLink, String targetObject)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void createSymlink(CreateSymlinkRequest createSymlinkRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public OSSSymlink getSymlink(String bucketName, String symLink) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public OSSSymlink getSymlink(GenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public GenericResult processObject(ProcessObjectRequest processObjectRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void createUdf(CreateUdfRequest createUdfRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UdfInfo getUdfInfo(UdfGenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<UdfInfo> listUdfs() throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteUdf(UdfGenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void uploadUdfImage(UploadUdfImageRequest uploadUdfImageRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<UdfImageInfo> getUdfImageInfo(UdfGenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteUdfImage(UdfGenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void createUdfApplication(CreateUdfApplicationRequest createUdfApplicationRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UdfApplicationInfo getUdfApplicationInfo(UdfGenericRequest genericRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public List<UdfApplicationInfo> listUdfApplications() throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void deleteUdfApplication(UdfGenericRequest genericRequest) throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void upgradeUdfApplication(UpgradeUdfApplicationRequest upgradeUdfApplicationRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public void resizeUdfApplication(ResizeUdfApplicationRequest resizeUdfApplicationRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public UdfApplicationLog getUdfApplicationLog(GetUdfApplicationLogRequest getUdfApplicationLogRequest)
            throws OSSException, ClientException {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    public synchronized URI getEndpoint() {
        throw new UnsupportedOperationException(unsupportedMsg);
    }


    public synchronized void setEndpoint(String endpoint) {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public synchronized void switchCredentials(Credentials creds) {
        if (creds == null) {
            throw new IllegalArgumentException("creds should not be null.");
        }
        auth = Auth.create(creds.getAccessKeyId(), creds.getSecretAccessKey());
        _bucketManager = new BucketManager(auth, config);
        this.credsProvider.setCredentials(creds);
//        throw new UnsupportedOperationException(unsupportedMsg);
    }

    public CredentialsProvider getCredentialsProvider() {
        return this.credsProvider;
//        throw new UnsupportedOperationException(unsupportedMsg);
    }

    public ClientConfiguration getClientConfiguration() {
        throw new UnsupportedOperationException(unsupportedMsg);
    }

    @Override
    public synchronized void shutdown() {
        _bucketManager = null;
        _uploadManager = null;
        auth = null;
        config = null;
    }


    //////////////

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

    private DomainCache domainCache = new DomainCache();

    private String getHttpDomain(String bucketName) {
        Host host = domainCache.get(bucketName);
        if (host != null && System.currentTimeMillis() / 1000 < host.create + 60 * 10) {
            return host.host;
        }
        if (host != null) {
            domainCache.remove(bucketName);
        }
        String[] domains = {};
        try {
            domains = getBucketManager().domainList(bucketName);
        } catch (QiniuException e) {
            throwAliException(e);
        }
        String domain = "";
        try {
            domain = domains[0];
        } catch (Exception e) {
            throw new ClientException("do not have domain from Qiniu");
        }
        domainCache.put(bucketName, new Host(domain));
        return domain;
    }

    private void removeHttpDomainCache(String bucketName) {
        domainCache.remove(bucketName);
    }

    class Host {
        String host;
        long create;

        Host(String host) {
            this.host = host;
            this.create = System.currentTimeMillis() / 1000;
        }
    }

    class DomainCache extends LinkedHashMap<String, Host> {
        private int limit;

        public DomainCache() {
            this(256);
        }

        public DomainCache(int limit) {
            super(limit, 1.0f, true);
            this.limit = limit;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Host> eldest) {
            return this.size() > this.limit;
        }
    }

}
