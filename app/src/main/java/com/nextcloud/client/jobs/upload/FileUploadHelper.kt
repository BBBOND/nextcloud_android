/*
 * Nextcloud Android client application
 *
 * @author Alper Ozturk
 * Copyright (C) 2023 Alper Ozturk
 * Copyright (C) 2023 Nextcloud GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.nextcloud.client.jobs.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.device.PowerManagementService
import com.nextcloud.client.jobs.BackgroundJobManager
import com.nextcloud.client.jobs.upload.FileUploadWorker.Companion.currentUploadFileOperation
import com.nextcloud.client.network.ConnectivityService
import com.nextcloud.java.util.Optional
import com.owncloud.android.MainApp
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.datamodel.UploadsStorageManager
import com.owncloud.android.datamodel.UploadsStorageManager.UploadStatus
import com.owncloud.android.db.OCUpload
import com.owncloud.android.db.UploadResult
import com.owncloud.android.files.services.NameCollisionPolicy
import com.owncloud.android.lib.common.network.OnDatatransferProgressListener
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import com.owncloud.android.utils.FileUtil
import java.io.File
import javax.inject.Inject

@Suppress("TooManyFunctions")
class FileUploadHelper {

    @Inject
    lateinit var backgroundJobManager: BackgroundJobManager

    @Inject
    lateinit var accountManager: UserAccountManager

    @Inject
    lateinit var uploadsStorageManager: UploadsStorageManager

    init {
        MainApp.getAppComponent().inject(this)
    }

    companion object {
        private val TAG = FileUploadWorker::class.java.simpleName

        val mBoundListeners = HashMap<String, OnDatatransferProgressListener>()

        private var instance: FileUploadHelper? = null

        fun instance(): FileUploadHelper {
            return instance ?: synchronized(this) {
                instance ?: FileUploadHelper().also { instance = it }
            }
        }

        fun buildRemoteName(accountName: String, remotePath: String): String {
            return accountName + remotePath
        }
    }

    @Suppress("ComplexCondition")
    fun retryFailedUploads(
        uploadsStorageManager: UploadsStorageManager,
        connectivityService: ConnectivityService,
        accountManager: UserAccountManager,
        powerManagementService: PowerManagementService
    ) {
        val failedUploads = uploadsStorageManager.failedUploads
        if (failedUploads == null || failedUploads.isEmpty()) {
            return
        }

        val (gotNetwork, _, gotWifi) = connectivityService.connectivity
        val batteryStatus = powerManagementService.battery
        val charging = batteryStatus.isCharging || batteryStatus.isFull
        val isPowerSaving = powerManagementService.isPowerSavingEnabled
        var uploadUser = Optional.empty<User>()
        for (failedUpload in failedUploads) {
            // 1. extract failed upload owner account and cache it between loops (expensive query)
            if (!uploadUser.isPresent || !uploadUser.get().nameEquals(failedUpload.accountName)) {
                uploadUser = accountManager.getUser(failedUpload.accountName)
            }
            val isDeleted = !File(failedUpload.localPath).exists()
            if (isDeleted) {
                // 2A. for deleted files, mark as permanently failed
                if (failedUpload.lastResult != UploadResult.FILE_NOT_FOUND) {
                    failedUpload.lastResult = UploadResult.FILE_NOT_FOUND
                    uploadsStorageManager.updateUpload(failedUpload)
                }
            } else if (!isPowerSaving && gotNetwork &&
                canUploadBeRetried(failedUpload, gotWifi, charging) && !connectivityService.isInternetWalled
            ) {
                // 2B. for existing local files, try restarting it if possible
                retryUpload(failedUpload, uploadUser.get())
            }
        }
    }

    @Suppress("LongParameterList")
    fun uploadNewFiles(
        user: User,
        localPaths: Array<String>,
        remotePaths: Array<String>,
        localBehavior: Int,
        createRemoteFolder: Boolean,
        createdBy: Int,
        requiresWifi: Boolean,
        requiresCharging: Boolean,
        nameCollisionPolicy: NameCollisionPolicy
    ) {
        val uploads = localPaths.mapIndexed { index, localPath ->
            OCUpload(localPath, remotePaths[index], user.accountName).apply {
                this.nameCollisionPolicy = nameCollisionPolicy
                isUseWifiOnly = requiresWifi
                isWhileChargingOnly = requiresCharging
                uploadStatus = UploadStatus.UPLOAD_IN_PROGRESS
                this.createdBy = createdBy
                isCreateRemoteFolder = createRemoteFolder
                localAction = localBehavior
            }
        }
        uploadsStorageManager.storeUploads(uploads)
        backgroundJobManager.startFilesUploadJob(user)
    }

    fun cancelFileUpload(remotePath: String, accountName: String) {
        try {
            val user = accountManager.getUser(accountName).get()

            // need to update now table in mUploadsStorageManager,
            // since the operation will not get to be run by FileUploader#uploadFile
            uploadsStorageManager.removeUpload(accountName, remotePath)

            cancelAndRestartUploadJob(user)
        } catch (e: NoSuchElementException) {
            Log_OC.e(TAG, "Error cancelling current upload because user does not exist!")
        }
    }

    private fun cancelAndRestartUploadJob(user: User) {
        backgroundJobManager.run {
            cancelFilesUploadJob(user)
            startFilesUploadJob(user)
        }
    }

    @Suppress("ReturnCount")
    fun isUploading(user: User?, file: OCFile?): Boolean {
        if (user == null || file == null || !backgroundJobManager.isStartFileUploadJobScheduled(user)) {
            return false
        }

        val upload: OCUpload = uploadsStorageManager.getUploadByRemotePath(file.remotePath) ?: return false
        return upload.uploadStatus == UploadStatus.UPLOAD_IN_PROGRESS
    }

    private fun canUploadBeRetried(upload: OCUpload, gotWifi: Boolean, isCharging: Boolean): Boolean {
        val file = File(upload.localPath)
        val needsWifi = upload.isUseWifiOnly
        val needsCharging = upload.isWhileChargingOnly
        return file.exists() && (!needsWifi || gotWifi) && (!needsCharging || isCharging)
    }

    @Suppress("ReturnCount")
    fun isUploadingNow(upload: OCUpload?): Boolean {
        val currentUploadFileOperation = currentUploadFileOperation
        if (currentUploadFileOperation == null || currentUploadFileOperation.user == null) return false
        if (upload == null || upload.accountName != currentUploadFileOperation.user.accountName) return false

        return if (currentUploadFileOperation.oldFile != null) {
            // For file conflicts check old file remote path
            upload.remotePath == currentUploadFileOperation.remotePath ||
                upload.remotePath == currentUploadFileOperation.oldFile!!
                    .remotePath
        } else {
            upload.remotePath == currentUploadFileOperation.remotePath
        }
    }

    fun uploadUpdatedFile(
        user: User,
        existingFiles: Array<OCFile?>?,
        behaviour: Int,
        nameCollisionPolicy: NameCollisionPolicy
    ) {
        if (existingFiles == null) {
            return
        }

        Log_OC.d(this, "upload updated file")

        val uploads = existingFiles.map { file ->
            file?.let {
                OCUpload(file, user).apply {
                    fileSize = file.fileLength
                    this.nameCollisionPolicy = nameCollisionPolicy
                    isCreateRemoteFolder = true
                    this.localAction = behaviour
                    isUseWifiOnly = false
                    isWhileChargingOnly = false
                    uploadStatus = UploadStatus.UPLOAD_IN_PROGRESS
                }
            }
        }
        uploadsStorageManager.storeUploads(uploads)
        backgroundJobManager.startFilesUploadJob(user)
    }

    fun retryUpload(upload: OCUpload, user: User) {
        Log_OC.d(this, "retry upload")

        upload.uploadStatus = UploadStatus.UPLOAD_IN_PROGRESS
        uploadsStorageManager.updateUpload(upload)

        backgroundJobManager.startFilesUploadJob(user)
    }

    fun cancel(accountName: String) {
        uploadsStorageManager.removeUploads(accountName)
        cancelAndRestartUploadJob(accountManager.getUser(accountName).get())
    }

    fun addUploadTransferProgressListener(
        listener: OnDatatransferProgressListener,
        targetKey: String
    ) {
        mBoundListeners[targetKey] = listener
    }

    fun removeUploadTransferProgressListener(
        listener: OnDatatransferProgressListener,
        targetKey: String
    ) {
        if (mBoundListeners[targetKey] === listener) {
            mBoundListeners.remove(targetKey)
        }
    }

    @Suppress("MagicNumber")
    fun isSameFileOnRemote(user: User, localFile: File, remotePath: String, context: Context): Boolean {
        // Compare remote file to local file
        val localLastModifiedTimestamp = localFile.lastModified() / 1000 // remote file timestamp in milli not micro sec
        val localCreationTimestamp = FileUtil.getCreationTimestamp(localFile)
        val localSize: Long = localFile.length()

        val operation = ReadFileRemoteOperation(remotePath)
        val result: RemoteOperationResult<*> = operation.execute(user, context)
        if (result.isSuccess) {
            val remoteFile = result.data[0] as RemoteFile
            return remoteFile.size == localSize &&
                localCreationTimestamp != null &&
                localCreationTimestamp == remoteFile.creationTimestamp &&
                remoteFile.modifiedTimestamp == localLastModifiedTimestamp * 1000
        }
        return false
    }

    class UploadNotificationActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val accountName = intent.getStringExtra(FileUploadWorker.EXTRA_ACCOUNT_NAME)
            val remotePath = intent.getStringExtra(FileUploadWorker.EXTRA_REMOTE_PATH)
            val action = intent.action

            if (FileUploadWorker.ACTION_CANCEL_BROADCAST == action) {
                Log_OC.d(
                    FileUploadWorker.TAG,
                    "Cancel broadcast received for file " + remotePath + " at " + System.currentTimeMillis()
                )
                if (accountName == null || remotePath == null) {
                    return
                }

                instance().cancelFileUpload(remotePath, accountName)
            }
        }
    }
}
