package com.ekeditor.app;

interface IEkFileService {
    byte[] readBackup();
    String createBackup();
    String prepareRestoreBackup(in byte[] bytes, in byte[] expectedSha256);
}
