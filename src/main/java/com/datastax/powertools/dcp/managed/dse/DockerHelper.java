/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.powertools.dcp.managed.dse;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.google.common.util.concurrent.Uninterruptibles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DockerHelper {

    private DockerClientConfig config;
    private DockerClient dockerClient;
    private CreateContainerResponse container;
    private Logger logger = LoggerFactory.getLogger(DockerHelper.class);

    public DockerHelper() {
        this.config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.dockerClient = DockerClientBuilder.getInstance(config).build();

    }

    public void startDSE() {

        String img = "datastax/ddac";
        String tag = "latest";
        String name = "ddac";
        List<Integer> ports = Arrays.asList(9042);
        List<String> volumeDescList = Arrays.asList();;
        List<String> envList = Arrays.asList("DS_LICENSE=accept");
        List<String> cmdList = Arrays.asList();

        String containerId = startDocker(img,tag,name, ports,volumeDescList, envList, cmdList);

        LogContainerResultCallback loggingCallback = new
                LogContainerResultCallback();

        waitForPort("localhost",9042, Duration.ofMillis(50000), logger, true);

    }

    public static boolean waitForPort(String hostname, int port, Duration timeout, Logger logger, boolean quiet)
    {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while(System.nanoTime() < deadlineNanos)
        {
            SocketChannel channel = null;

            try
            {
                logger.info("Checking {}:{}", hostname,port);
                channel = SocketChannel.open(new InetSocketAddress(hostname, port));
            }
            catch(IOException e)
            {
                Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
            }

            if (channel != null)
            {
                try
                {
                    channel.close();
                }
                catch (IOException e)
                {
                    //Close quietly
                }

                logger.info("Connected to {}:{}", hostname,port);
                return true;
            }
        }

        //The port never opened
        if (!quiet)
        {
            logger.warn("Failed to connect to {}:{} after {} sec", hostname, port, timeout.toMillis() / 1000);
        }

        return false;
    }

    private String startDocker(String IMG, String tag, String name, List<Integer> ports, List<String> volumeDescList, List<String> envList, List<String> cmdList) {
        ListContainersCmd listContainersCmd = dockerClient.listContainersCmd().withStatusFilter(Arrays.asList("exited"));
        listContainersCmd.getFilters().put("name", Arrays.asList(name));
        List<Container> stoppedContainers = null;
        try {
            stoppedContainers = listContainersCmd.exec();
            for (Container stoppedContainer : stoppedContainers) {
                String id = stoppedContainer.getId();
                logger.info("Removing exited container: " + id);
                dockerClient.removeContainerCmd(id).exec();
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Unable to contact docker, make sure docker is up and try again.");
            logger.error("If docker is installed make sure this user has access to the docker group.");
            logger.error("$ sudo gpasswd -a ${USER} docker && newgrp docker");
            System.exit(1);
        }

        Container containerId = searchContainer(name);
        if (containerId != null) {
            return containerId.getId();
        }

        Info info = dockerClient.infoCmd().exec();
        dockerClient.buildImageCmd();

        String term = IMG.split("/")[1];
        //List<SearchItem> dockerSearch = dockerClient.searchImagesCmd(term).exec();
        List<Image> dockerList = dockerClient.listImagesCmd().withImageNameFilter(IMG).exec();
        if (dockerList.size() == 0) {
            dockerClient.pullImageCmd(IMG)
                    .withTag(tag)
                    .exec(new PullImageResultCallback()).awaitSuccess();

            dockerList = dockerClient.listImagesCmd().withImageNameFilter(IMG).exec();
            if (dockerList.size() == 0) {
                logger.error(String.format("Image %s not found, unable to automatically pull image." +
                                " Check `docker images`",
                        IMG));
                System.exit(1);
            }
        }
        logger.info("Search returned" + dockerList.toString());


        List<ExposedPort> tcpPorts = new ArrayList<>();
        List<PortBinding> portBindings = new ArrayList<>();
        for (Integer port : ports) {
            ExposedPort tcpPort = ExposedPort.tcp(port);
            Ports.Binding binding = new Ports.Binding("0.0.0.0", String.valueOf(port));
            PortBinding pb = new PortBinding(binding, tcpPort);

            tcpPorts.add(tcpPort);
            portBindings.add(pb);
        }

        List<Volume> volumeList = new ArrayList<>();
        List<Bind> volumeBindList = new ArrayList<>();
        for (String volumeDesc : volumeDescList) {
            String volFrom = volumeDesc.split(":")[0];
            String volTo = volumeDesc.split(":")[1];
            Volume vol = new Volume(volTo);
            volumeList.add(vol);
            volumeBindList.add(new Bind(volFrom, vol));
        }


        CreateContainerResponse containerResponse;
        if (envList == null) {
            containerResponse = dockerClient.createContainerCmd(IMG + ":" + tag)
                    .withCmd(cmdList)
                    .withExposedPorts(tcpPorts)
                    .withHostConfig(
                            new HostConfig()
                                    .withPortBindings(portBindings)
                                    .withPublishAllPorts(true)
                                    .withBinds(volumeBindList)
                    )
                    .withName(name)
                    //.withVolumes(volumeList)
                    .exec();
        } else {
            containerResponse = dockerClient.createContainerCmd(IMG + ":" + tag)
                    .withEnv(envList)
                    .withExposedPorts(tcpPorts)
                    .withHostConfig(
                            new HostConfig()
                                    .withPortBindings(portBindings)
                                    .withPublishAllPorts(true)
                                    .withBinds(volumeBindList)
                    )
                    .withName(name)
                    //.withVolumes(volumeList)
                    .exec();
        }

        dockerClient.startContainerCmd(containerResponse.getId()).exec();

        return containerResponse.getId();
    }



    private Container searchContainer(String name) {

        ListContainersCmd listContainersCmd = dockerClient.listContainersCmd().withStatusFilter(Arrays.asList("running"));
        listContainersCmd.getFilters().put("name", Arrays.asList(name));
        List<Container> runningContainers = null;
        try {
            runningContainers = listContainersCmd.exec();
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("Unable to contact docker, make sure docker is up and try again.");
            System.exit(1);
        }

        if (runningContainers.size() >= 1) {
            //Container test = runningContainers.get(0);
            logger.info(String.format("The container %s is already running", name));

            return runningContainers.get(0);
        }
        return null;
    }

    public void stopDSE()
    {
        if (container != null)
            dockerClient.stopContainerCmd(container.getId()).exec();
    }
}
