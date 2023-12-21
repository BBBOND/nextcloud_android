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

package com.nextcloud.client.files.downloader

import android.content.Context
import android.content.Intent
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.operations.DownloadFileOperation
import com.owncloud.android.ui.activity.FileActivity
import com.owncloud.android.ui.activity.FileDisplayActivity
import com.owncloud.android.ui.dialog.SendShareDialog
import com.owncloud.android.ui.fragment.OCFileListFragment
import com.owncloud.android.ui.preview.PreviewImageActivity
import com.owncloud.android.ui.preview.PreviewImageFragment

class FilesDownloadIntents(private val context: Context) {

    fun newDownloadIntent(
        download: DownloadFileOperation,
        linkedToRemotePath: String
    ): Intent {
        return Intent(FilesDownloadWorker.getDownloadAddedMessage()).apply {
            putExtra(FilesDownloadWorker.ACCOUNT_NAME, download.user.accountName)
            putExtra(FilesDownloadWorker.EXTRA_REMOTE_PATH, download.remotePath)
            putExtra(FilesDownloadWorker.EXTRA_LINKED_TO_PATH, linkedToRemotePath)
            setPackage(context.packageName)
        }
    }

    fun downloadFinishedIntent(
        download: DownloadFileOperation,
        downloadResult: RemoteOperationResult<*>,
        unlinkedFromRemotePath: String?
    ): Intent {
        return Intent(FilesDownloadWorker.getDownloadFinishMessage()).apply {
            putExtra(FilesDownloadWorker.EXTRA_DOWNLOAD_RESULT, downloadResult.isSuccess)
            putExtra(FilesDownloadWorker.ACCOUNT_NAME, download.user.accountName)
            putExtra(FilesDownloadWorker.EXTRA_REMOTE_PATH, download.remotePath)
            putExtra(OCFileListFragment.DOWNLOAD_BEHAVIOUR, download.behaviour)
            putExtra(SendShareDialog.ACTIVITY_NAME, download.activityName)
            putExtra(SendShareDialog.PACKAGE_NAME, download.packageName)
            if (unlinkedFromRemotePath != null) {
                putExtra(FilesDownloadWorker.EXTRA_LINKED_TO_PATH, unlinkedFromRemotePath)
            }
            setPackage(context.packageName)
        }
    }

    fun detailsIntent(operation: DownloadFileOperation?): Intent {
        return if (operation != null) {
            if (PreviewImageFragment.canBePreviewed(operation.file)) {
                Intent(context, PreviewImageActivity::class.java)
            } else {
                Intent(context, FileDisplayActivity::class.java)
            }.apply {
                putExtra(FileActivity.EXTRA_FILE, operation.file)
                putExtra(FileActivity.EXTRA_USER, operation.user)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent()
        }
    }
}
