/**
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.internal.monitor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;

import com.dangdang.ddframe.job.api.JobConfiguration;
import com.dangdang.ddframe.job.internal.config.ConfigurationService;
import com.dangdang.ddframe.job.internal.util.SensitiveInfoUtils;
import com.dangdang.ddframe.reg.base.CoordinatorRegistryCenter;
import com.google.common.base.Joiner;

import lombok.extern.slf4j.Slf4j;

/**
 * 作业监控服务.
 * 
 * @author caohao
 */
@Slf4j
public class MonitorService {
    
    public static final String DUMP_COMMAND = "dump";
    
    private final String jobName;
    
    private final CoordinatorRegistryCenter coordinatorRegistryCenter;
    
    private final ConfigurationService configService;
    
    private ServerSocket serverSocket;
    
    private volatile boolean closed;
    
    public MonitorService(final CoordinatorRegistryCenter coordinatorRegistryCenter, final JobConfiguration jobConfiguration) {
        jobName = jobConfiguration.getJobName();
        this.coordinatorRegistryCenter = coordinatorRegistryCenter;
        configService = new ConfigurationService(coordinatorRegistryCenter, jobConfiguration);
    }
    
    /**
     * 初始化作业监听服务.
     */
    public void listen() {
        int port = configService.getMonitorPort();
        if (port < 0) {
            return;
        }
        try {
            log.info("Elastic job: monitor service is running, the port is '{}'", port);
            openSocketForMonitor(port);
        } catch (final IOException ex) {
            log.warn(ex.getMessage());
        }
    }
    
    private void openSocketForMonitor(final int port) throws IOException {
        serverSocket = new ServerSocket(port);
        new Thread() {
            
            @Override
            public void run() {
                while (!closed) {
                    try {
                        process(serverSocket.accept());
                    } catch (final IOException ex) {
                        log.warn(ex.getMessage());
                    }
                }
            }
        }.start();
    }
    
    private void process(final Socket socket) {
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                Socket autoCloseSocket = socket;
            ) {
            String cmdLine = reader.readLine();
            if (null != cmdLine && DUMP_COMMAND.equalsIgnoreCase(cmdLine)) {
                List<String> result = new ArrayList<>();
                dumpDirectly("/" + jobName, result);
                outputMessage(writer, Joiner.on("\n").join(SensitiveInfoUtils.filterSenstiveIps(result)) + "\n");
            }
        } catch (final IOException ex) {
            log.warn(ex.getMessage());
        }
    }
    
    private void dumpDirectly(final String path, final List<String> result) {
        for (String each : coordinatorRegistryCenter.getChildrenKeys(path)) {
            String zkPath = path + "/" + each;
            String zkValue = coordinatorRegistryCenter.get(zkPath);
            TreeCache treeCache = (TreeCache) coordinatorRegistryCenter.getRawCache("/" + jobName);
            ChildData treeCacheData = treeCache.getCurrentData(zkPath);
            String treeCachePath =  null == treeCacheData ? "" : treeCacheData.getPath();
            String treeCacheValue = null == treeCacheData ? "" : new String(treeCacheData.getData());
            if (zkValue.equals(treeCacheValue) && zkPath.equals(treeCachePath)) {
                result.add(Joiner.on(" | ").join(zkPath, zkValue));
            } else {
                result.add(Joiner.on(" | ").join(zkPath, zkValue, treeCachePath, treeCacheValue));
            }
            dumpDirectly(zkPath, result);
        }
    }
    
    private void outputMessage(final BufferedWriter outputWriter, final String msg) throws IOException {
        outputWriter.append(msg);
        outputWriter.flush();
    }
    
    /**
     * 关闭作业监听服务.
     */
    public void close() {
        closed = true;
        if (null != serverSocket && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (final IOException ex) {
                log.warn(ex.getMessage());
            }
        }
    }
}
