/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
'use strict';

import {QuickPickItem} from 'vscode';
import {
    NotificationType,
    RequestType,
    ShowMessageParams
} from 'vscode-languageclient';

export interface ShowStatusMessageParams extends ShowMessageParams {
    /**
     * The timeout
     */
    timeout?: number;
}

export namespace StatusMessageRequest {
    export const type = new NotificationType<ShowStatusMessageParams, void>('window/showStatusBarMessage');
};

export interface ShowQuickPickParams {
    /**
     * A string to show as placeholder in the input box to guide the user what to pick on.
     */
    placeHolder: string;
    /**
     * An optional flag to make the picker accept multiple selections.
     */
    canPickMany?: boolean;
    /**
     * A list of items.
     */
    items: QuickPickItem[];
}

export namespace QuickPickRequest {
    export const type = new RequestType<ShowQuickPickParams, QuickPickItem[], void, void>('window/showQuickPick');
}

export interface ShowInputBoxParams {
    /**
     * The text to display underneath the input box.
     */
    prompt: string;
    /**
     * The value to prefill in the input box.
     */
    value: string;
}

export namespace InputBoxRequest {
    export const type = new RequestType<ShowInputBoxParams, string | undefined, void, void>('window/showInputBox');
}