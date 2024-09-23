/*
 * Copyright (C) 2017-2024 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.xyz.jobs.steps.outputs;

public class FileStatistics extends ModelBasedOutput {
    private long rowsUploaded;
    private long bytesUploaded;
    private int filesUploaded;

    public long getRowsUploaded() {
        return rowsUploaded;
    }

    public void setRowsUploaded(long rowsUploaded) {
        this.rowsUploaded = rowsUploaded;
    }

    public FileStatistics withRowsUploaded(long rowsUploaded) {
        setRowsUploaded(rowsUploaded);
        return this;
    }

    public long getBytesUploaded() {
        return bytesUploaded;
    }

    public void setBytesUploaded(long bytesUploaded) {
        this.bytesUploaded = bytesUploaded;
    }

    public FileStatistics withBytesUploaded(long bytesUploaded) {
        setBytesUploaded(bytesUploaded);
        return this;
    }

    public int getFilesUploaded() {
        return filesUploaded;
    }

    public void setFilesUploaded(int filesUploaded) {
        this.filesUploaded = filesUploaded;
    }

    public FileStatistics withFilesUploaded(int filesUploaded) {
        setFilesUploaded(filesUploaded);
        return this;
    }
}
