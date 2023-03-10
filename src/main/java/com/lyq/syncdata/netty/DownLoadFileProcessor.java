package com.lyq.syncdata.netty;

import com.alibaba.fastjson.JSON;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.lyq.syncdata.constant.CommandEnum;
import com.lyq.syncdata.constant.ServerResponseEnum;
import com.lyq.syncdata.pojo.*;
import com.lyq.syncdata.service.CheckPointService;
import com.lyq.syncdata.util.TimeUtil;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * created by lyq
 */
public class DownLoadFileProcessor implements SyncDataCommandProcessor{

    private static final CommandEnum dowonloadFileEnum = CommandEnum.DOWONLOAD_FILE;
    private static final CommandEnum dowonloadPictureEnum = CommandEnum.DOWONLOAD_PICTURE;
    private final ThreadPoolExecutor commonWorks;
    private final AtomicInteger fileId = new AtomicInteger(0);
    private final RequestManager requestManager;
    private volatile boolean connection = true;

    public DownLoadFileProcessor(ThreadPoolExecutor commonWorks, RequestManager requestManager) {
        this.commonWorks = commonWorks;
        this.requestManager = requestManager;
    }

    @Override
    public void processor(ChannelHandlerContext ctx, SyncDataCommand command) {
        File storeRootDir = new File(SaveDataProcessor.getRootDir());
        File[] files = storeRootDir.listFiles();
        if(files != null){
            List<String> paths = Arrays.stream(files).map(File::getAbsolutePath).filter(path -> !path.contains(".DS_Store")).collect(Collectors.toList());
            Progress progress = JSON.parseObject(command.getBody(), Progress.class);
            if(paths.size() > 0){
                if(dowonloadPictureEnum.getCode().equals(command.getCode())){
                    paths = paths.stream().filter((path) -> {
                        String suffix = path.substring(path.lastIndexOf("."));
                        return !suffix.contains("mp4");
                    }).collect(Collectors.toList());
                }
                List<String> smalleFilePath = new ArrayList<>();
                List<String> notCompleteFile = new ArrayList<>();
                List<CheckPointFileInfo> needUploadFiles = CheckPointService.getNeedUploadFiles(progress, paths).stream().sorted(
                        (fist, last) -> {
                            long fistlong = getRealCreateTime(new File(fist.getPath()));
                            long lastlong = getRealCreateTime(new File(last.getPath()));
                            return Long.compare(fistlong, lastlong);
                        }
                ).collect(Collectors.toList());
                ClientMonitor clientMonitor = new ClientMonitor();
                calculationTotalSize(needUploadFiles, smalleFilePath, notCompleteFile, clientMonitor);
                clientMonitor.setServerFileCount(paths.size());
                clientMonitor.setFileCount(needUploadFiles.size());
                CommandCallable commandCallable = (syncDataCommand) -> {
                    ServerResponse serverResponse = JSON.parseObject(syncDataCommand.getBody(), ServerResponse.class);
                    if (ServerResponseEnum.SAVE_SUCCESS.getCode().equals(serverResponse.getCode())) {
                        for (CheckPointFileInfo checkPointFileInfo : needUploadFiles) {
                            if(!connection){
                                ctx.flush();
                                break;
                            }
                            String path = checkPointFileInfo.getPath();
                            if (notCompleteFile.contains(path)) {
                                sendBigData(new File(path), ctx, false, checkPointFileInfo.getStartIndex());
                            } else if (smalleFilePath.contains(path)) {
                                sendSmalleData(new File(path), ctx);
                            } else {
                                sendBigData(new File(path), ctx);
                            }
                        }
                    }
                };
                SyncDataCommand serverCommand = buildCommand(CommandEnum.GET_SYNC_PROGRESS, clientMonitor, null,true);
                serverCommand.setCommandId(command.getCommandId());
                requestManager.put(command.getCommandId(), new RequestFuture(commandCallable, command));
                ctx.channel().writeAndFlush(serverCommand);
            }
        }
    }

    public void calculationTotalSize(List<CheckPointFileInfo> needSaveDataPath, List<String> smalleFilePath, List<String> notCompleteFile, ClientMonitor clientMonitor){
        for (CheckPointFileInfo checkPointFileInfo : needSaveDataPath) {
            File file = new File(checkPointFileInfo.getPath());
            long needUploadTotalSize;
            if(checkPointFileInfo.getCompleteFile()){
                needUploadTotalSize = file.length();
                clientMonitor.setTotalFileSize(clientMonitor.getTotalFileSize() + needUploadTotalSize);
            }else{
                needUploadTotalSize = file.length() - checkPointFileInfo.getStartIndex();
                clientMonitor.setTotalFileSize(clientMonitor.getTotalFileSize() + needUploadTotalSize);
                clientMonitor.setNotCompleteFileSize(clientMonitor.getNotCompleteFileSize() + needUploadTotalSize);
                clientMonitor.setNotCompleteFileCount(clientMonitor.getNotCompleteFileCount() + 1);
                notCompleteFile.add(checkPointFileInfo.getPath());
                continue;
            }
            //2M??????????????????
            if(needUploadTotalSize < 2242880){
                smalleFilePath.add(checkPointFileInfo.getPath());
            }
        }
    }

    public void sendSmalleData(File file, ChannelHandlerContext ctx){
        commonWorks.execute(() -> {
            while(!ctx.channel().isWritable() && connection){

            }
            try {
                UploadFile uploadFile = buildUploadFile(file);
                uploadFile.setBigFile(false);
                SyncDataCommand command = buildCommand(CommandEnum.DOWONLOAD_FILE, uploadFile, (syncDataCommand) -> {
                    ServerResponse serverResponse = JSON.parseObject(syncDataCommand.getBody(), ServerResponse.class);
                    checkAndFlush(ctx);
                    if (!ServerResponseEnum.SAVE_SUCCESS.getCode().equals(serverResponse.getCode()) && connection) {
                        sendSmalleData(file, ctx);
                    }
                });
                if(ctx.channel().isOpen()){
                    ctx.channel().writeAndFlush(command);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }


    public void sendBigData(File file, ChannelHandlerContext ctx){
        sendBigData(file, ctx, true, 0);
    }

    public void sendBigData(File bigFile, ChannelHandlerContext ctx ,boolean complete, long readIndex){
        commonWorks.execute(() -> {
            if(ctx.channel().isWritable() && connection){
                try {
                    UploadFile uploadFile = new UploadFile();
                    getFileByPath(bigFile, uploadFile);
                    if(complete){
                        uploadFile.setTotalSize(bigFile.length());
                    }else{
                        uploadFile.setTotalSize(bigFile.length() - readIndex);
                    }
                    uploadFile.setBigFile(true);
                    uploadFile.setId(fileId.incrementAndGet());
                    long totalSize = uploadFile.getTotalSize();
                    RandomAccessFile randomAccessFile = new RandomAccessFile(bigFile, "r");
                    //long preSize = 1048576;
                    long preSize = 2242880;
                    //3m
                    int multipleCount = (int) (totalSize / preSize);
                    long remainCount = totalSize - (multipleCount * preSize);
                    int cicleCount = multipleCount + (remainCount == 0 ? 0 : 1);

                    for (int i = 0; i < cicleCount; i++) {
                        while(!ctx.channel().isWritable() && connection){
                        }
                        UploadFile copyUploadFile = uploadFile.copy();
                        int position = (int) (((i * preSize) == 0 ? 0 : (int) (i * preSize)) + readIndex);
                        copyUploadFile.setWriteIndex((long) position);
                        byte[] b;
                        if(i < cicleCount - 1){
                            b = new byte[(int) preSize];
                            copyUploadFile.setEnd(false);
                        }else{
                            b = new byte[(int) remainCount];
                            copyUploadFile.setEnd(true);
                        }

                        randomAccessFile.seek(position);
                        randomAccessFile.read(b);
                        copyUploadFile.setContain(b);

                        SyncDataCommand command = buildCommand(CommandEnum.DOWONLOAD_FILE, copyUploadFile, (syncDataCommand) -> {
                            ServerResponse serverResponse = JSON.parseObject(syncDataCommand.getBody(), ServerResponse.class);
                            checkAndFlush(ctx);
                            if (!ServerResponseEnum.SAVE_SUCCESS.getCode().equals(serverResponse.getCode()) && connection) {
                                sendBigData(bigFile, ctx, false, position);
                            }
                        });
                        if(ctx.channel().isOpen()){
                            ctx.channel().writeAndFlush(command);
                        }else{
                            break;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }else{
                sendBigData(bigFile, ctx);
            }
        });
    }

    public SyncDataCommand buildCommand(CommandEnum commandEnum, Object body, CommandCallable commandCallable){
        return buildCommand(commandEnum, body, commandCallable, false);
    }
    public SyncDataCommand buildCommand(CommandEnum commandEnum, Object body, CommandCallable commandCallable, boolean newId){
        SyncDataCommand command = new SyncDataCommand();
        command.setCode(commandEnum.getCode());
        command.setBody(JSON.toJSONBytes(body));
        command.setLength(command.getBody().length);
        if(!newId){
            command.setCommandId(requestManager.getCommandIdIncr().getAndIncrement());
            requestManager.put(command.getCommandId(), new RequestFuture(commandCallable, command));
        }
        return command;
    }

    public void getFileByPath(File file, UploadFile uploadFile) throws IOException {
        String path = file.getAbsolutePath();
        String abstractFileName = path.substring(path.lastIndexOf("/"));
        String suffix = "";
        String fileName;
        if(abstractFileName.contains(".")){
            suffix = abstractFileName.substring(abstractFileName.lastIndexOf(".") + 1);
            fileName = abstractFileName.substring(0, abstractFileName.lastIndexOf("."));
        }else{
            fileName = abstractFileName;
        }
        uploadFile.setSuffix(suffix);
        uploadFile.setFileName(fileName);
    }

    public UploadFile buildUploadFile(File file, boolean isBigFile) throws IOException {
        UploadFile uploadFile = new UploadFile();
        getFileByPath(file, uploadFile);
        if(!isBigFile){
            RandomAccessFile accseeFile = new RandomAccessFile(file, "r");
            byte[] b = new byte[(int) file.length()];
            while(accseeFile.read(b) != -1){
                //continue
            }
            accseeFile.close();
            uploadFile.setContain(b);
        }
        return uploadFile;
    }
    public UploadFile buildUploadFile(File file) throws IOException {
        return buildUploadFile(file, false);
    }

    public long getRealCreateTime(File file){
        Metadata metadata = null;
        long realCreateTime = 0L;
        try {
            metadata = ImageMetadataReader.readMetadata(file);
            for (Directory next : metadata.getDirectories()) {
                Collection<Tag> tags = next.getTags();
                boolean finash = false;
                for (Tag tag : tags) {
                    if (tag.getTagName().contains("Date/Time") && StringUtils.isNotBlank(tag.getDescription())) {
                        String timeString = tag.getDescription();
                        realCreateTime = TimeUtil.yyyyMMddHHmmss2TimeStamp(timeString);
                        finash = true;
                        break;
                    }
                }
                if(finash){
                    break;
                }
            }
        } catch (Exception e) {
            //continue
        }
        return realCreateTime;
    }

    @Override
    public boolean match(SyncDataCommand command) {
        return dowonloadFileEnum.getCode().equals(command.getCode()) || dowonloadPictureEnum.getCode().equals(command.getCode());
    }

    public void unconnection() {
        connection = false;
    }

    public void checkAndFlush(ChannelHandlerContext ctx){
        if(!connection){
            ctx.flush();
            ctx.close();
        }
    }
}
