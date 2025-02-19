/*
 * Changes to this file committed after and not including commit-id: ccc0d2c5f9a5ac661e60e6eaf138de7889928b8b
 * are released under the following license:
 *
 * This file is part of Hopsworks
 * Copyright (C) 2018, Logical Clocks AB. All rights reserved
 *
 * Hopsworks is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * Changes to this file committed before and including commit-id: ccc0d2c5f9a5ac661e60e6eaf138de7889928b8b
 * are released under the following license:
 *
 * Copyright (C) 2013 - 2018, Logical Clocks AB and RISE SICS AB. All rights reserved
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS  OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL  THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR  OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.hops.hopsworks.common.jupyter;

import io.hops.hopsworks.common.util.HopsUtils;
import io.hops.hopsworks.exceptions.JobException;
import io.hops.hopsworks.persistence.entity.jobs.configuration.DockerJobConfiguration;
import io.hops.hopsworks.persistence.entity.jupyter.JupyterProject;
import io.hops.hopsworks.persistence.entity.jupyter.JupyterSettings;
import io.hops.hopsworks.persistence.entity.project.Project;
import io.hops.hopsworks.persistence.entity.user.Users;
import io.hops.hopsworks.common.dao.jupyter.config.JupyterConfigFilesGenerator;
import io.hops.hopsworks.common.dao.jupyter.config.JupyterDTO;
import io.hops.hopsworks.common.dao.jupyter.config.JupyterFacade;
import io.hops.hopsworks.common.dao.jupyter.config.JupyterPaths;
import io.hops.hopsworks.common.integrations.LocalhostStereotype;
import io.hops.hopsworks.common.proxies.client.HttpClient;
import io.hops.hopsworks.common.util.OSProcessExecutor;
import io.hops.hopsworks.common.util.ProcessDescriptor;
import io.hops.hopsworks.common.util.ProcessResult;
import io.hops.hopsworks.common.util.ProjectUtils;
import io.hops.hopsworks.common.util.Settings;
import io.hops.hopsworks.exceptions.ServiceException;
import io.hops.hopsworks.restutils.RESTCodes;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * *
 * This class wraps a bash script with sudo rights that can be executed by the node['hopsworks']['user'].
 * /srv/hops/sbin/jupyter.sh
 * The bash script has several commands with parameters that can be exceuted.
 * This class provides a Java interface for executing the commands.
 */
@Stateless
@LocalhostStereotype
public class LocalHostJupyterProcessMgr extends JupyterManagerImpl implements JupyterManager {

  private static final Logger LOGGER = Logger.getLogger(LocalHostJupyterProcessMgr.class.getName());
  private static final int TOKEN_LENGTH = 48;
  private static final String JUPYTER_HOST_TEMPLATE = "http://%s:%d";
  private static final String PING_PATH = "/hopsworks-api/jupyter/%d/api/status";

  @EJB
  private Settings settings;
  @EJB
  private JupyterFacade jupyterFacade;
  @EJB
  private JupyterConfigFilesGenerator jupyterConfigFilesGenerator;
  @EJB
  private OSProcessExecutor osProcessExecutor;
  @EJB
  private HttpClient httpClient;
  @EJB
  private ProjectUtils projectUtils;
  
  private String jupyterHost;
  
  @PostConstruct
  public void init() {
    jupyterHost = settings.getJupyterHost();
  }

  @PreDestroy
  public void preDestroy() {
  }
  
  @Override
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public JupyterDTO startJupyterServer(Project project, String secretConfig, String hdfsUser, Users user,
    JupyterSettings js, String allowOrigin) throws ServiceException, JobException {
    
    String prog = settings.getSudoersDir() + "/jupyter.sh";
    
    Integer port = ThreadLocalRandom.current().nextInt(40000, 59999);
    JupyterPaths jp = jupyterConfigFilesGenerator.generateConfiguration(project, secretConfig, hdfsUser, user,
        js, port, allowOrigin);
    String secretDir = settings.getStagingDir() + Settings.PRIVATE_DIRS + js.getSecret();
    DockerJobConfiguration dockerJobConfiguration = (DockerJobConfiguration)js.getDockerConfig();

    String token = TokenGenerator.generateToken(TOKEN_LENGTH);
    String cid = "";
    
    // The Jupyter Notebook is running at: http://localhost:8888/?token=c8de56fa4deed24899803e93c227592aef6538f93025fe01
    int maxTries = 5;

    // kill any running servers for this user, clear cached entries
    while (maxTries > 0) {
      try {
        // use pidfile to kill any running servers
        ProcessDescriptor processDescriptor = new ProcessDescriptor.Builder()
            .addCommand("/usr/bin/sudo")
            .addCommand(prog)
            .addCommand("start")
            .addCommand(jp.getNotebookPath())
            .addCommand(settings.getHadoopSymbolicLinkDir() + "-" + settings.getHadoopVersion())
            .addCommand(hdfsUser)
            .addCommand(settings.getAnacondaProjectDir())
            .addCommand(port.toString())
            .addCommand(HopsUtils.getJupyterLogName(hdfsUser, port))
            .addCommand(secretDir)
            .addCommand(jp.getCertificatesDir())
            .addCommand(hdfsUser)
            .addCommand(token)
            .addCommand(js.getMode().getValue())
            .addCommand(projectUtils.getFullDockerImageName(project, false))
            .addCommand(String.valueOf(dockerJobConfiguration.getResourceConfig().getMemory()))
            .addCommand(String.valueOf(dockerJobConfiguration.getResourceConfig().getCores()))
            .redirectErrorStream(true)
            .setCurrentWorkingDirectory(new File(jp.getNotebookPath()))
            .setWaitTimeout(60L, TimeUnit.SECONDS)
            .build();

        String pidfile = jp.getRunDirPath() + "/jupyter.pid";
        ProcessResult processResult = osProcessExecutor.execute(processDescriptor);
        if (processResult.getExitCode() != 0) {
          String errorMsg = "Could not start Jupyter server. Exit code: " + processResult.getExitCode()
              + " Error: stdout: " + processResult.getStdout() + " stderr: " + processResult.getStderr();
          LOGGER.log(Level.SEVERE, errorMsg);
          throw new IOException(errorMsg);
        }
        // Read the pid for Jupyter Notebook
        cid = com.google.common.io.Files.readFirstLine(
            new File(pidfile), Charset.defaultCharset());

        return new JupyterDTO(port, token, cid, secretConfig, jp.getCertificatesDir());
      } catch (Exception ex) {
        LOGGER.log(Level.SEVERE, "Problem executing shell script to start Jupyter server", ex);
        maxTries--;
      }
    }

    String errorMsg = "Failed to start Jupyter";
    throw new ServiceException(RESTCodes.ServiceErrorCode.JUPYTER_START_ERROR, Level.SEVERE, errorMsg,
      errorMsg + " for project " + project);
  }

  @Override
  public void waitForStartup(Project project, Users user) throws TimeoutException {
    // Nothing to do as the start is blocking
  }

  @Override
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public void stopJupyterServer(Project project, Users user, String hdfsUsername, String jupyterHomePath, String cid,
      Integer port) throws ServiceException {
    if (jupyterHomePath == null || cid == null || port == null) {
      throw new IllegalArgumentException("Invalid arguments when stopping the Jupyter Server.");
    }
    // 1. Remove jupyter settings from the DB for this notebook first. If this fails, keep going to kill the notebook
    try {
      jupyterFacade.remove(hdfsUsername, port);
    } catch (Exception e) {
      LOGGER.severe("Problem when removing jupyter notebook entry from jupyter_project table: " + jupyterHomePath);
    }

    // 2. Then kill the jupyter notebook server. If this step isn't 
    String prog = settings.getSudoersDir() + "/jupyter.sh";
    if (jupyterHomePath.isEmpty()) {
      jupyterHomePath = "''";
    }
    int exitValue = 0;
    Integer id = 1;
    ProcessDescriptor.Builder pdBuilder = new ProcessDescriptor.Builder()
        .addCommand("/usr/bin/sudo")
        .addCommand(prog)
        .addCommand("kill")
        .addCommand(jupyterHomePath)
        .addCommand(cid)
        .addCommand(hdfsUsername)
        .setWaitTimeout(10L, TimeUnit.SECONDS);
    
    if (!LOGGER.isLoggable(Level.FINE)) {
      pdBuilder.ignoreOutErrStreams(true);
    }
    
    try {
      ProcessResult processResult = osProcessExecutor.execute(pdBuilder.build());
      LOGGER.log(Level.FINE, processResult.getStdout());
      exitValue = processResult.getExitCode();
    } catch (IOException ex) {
      throw new ServiceException(RESTCodes.ServiceErrorCode.JUPYTER_STOP_ERROR, Level.SEVERE, "exitValue: " + exitValue,
          ex.getMessage(), ex);
    }
    if (exitValue != 0) {
      throw new ServiceException(RESTCodes.ServiceErrorCode.JUPYTER_STOP_ERROR, Level.SEVERE,
        "exitValue: " + exitValue);
    }

  }

  @Override
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public void projectCleanup(Project project) {
    projectCleanup(LOGGER, project);
  }
  
  @Override
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public boolean ping(JupyterProject jupyterProject) {
    Integer jupyterPort = jupyterProject.getPort();
    HttpHost host = HttpHost.create(String.format(JUPYTER_HOST_TEMPLATE, jupyterHost, jupyterPort));
    String pingPath = String.format(PING_PATH, jupyterPort);
    try {
      URI authPath = new URIBuilder(pingPath).addParameter("token", jupyterProject.getToken()).build();
      HttpGet httpRequest = new HttpGet(authPath);
      return httpClient.execute(host, httpRequest, new ResponseHandler<Boolean>() {
        @Override
        public Boolean handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
          int status = response.getStatusLine().getStatusCode();
          return status == HttpStatus.SC_OK;
        }
      });
    } catch (URISyntaxException ex) {
      LOGGER.log(Level.SEVERE, "Could not parse URI to ping Jupyter server", ex);
      return false;
    } catch (IOException ex) {
      return false;
    }
  }

  @Override
  @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
  public List<JupyterProject> getAllNotebooks() {
    List<JupyterProject> allNotebooks = jupyterFacade.getAllNotebookServers();

    executeJupyterCommand("list");
    File file = new File(Settings.JUPYTER_PIDS);

    List<String> cidsRunning = new ArrayList<>();
    try {
      try (Scanner scanner = new Scanner(file)) {
        while (scanner.hasNextLine()) {
          cidsRunning.add(scanner.nextLine());
        }
      }
    } catch (FileNotFoundException e) {
      LOGGER.warning("Invalid pids in file: " + Settings.JUPYTER_PIDS);
    }

    List<String> cidsOrphaned = new ArrayList<>(cidsRunning);

    for (String cid : cidsRunning) {
      boolean foundCid = false;
      int i = 0;
      while (i < allNotebooks.size() && !foundCid) {
        if (cid.equals(allNotebooks.get(i).getCid())) {
          foundCid = true;
          cidsOrphaned.remove(cid);
          i += 1;
        }
      }
    }

    for (String cid : cidsOrphaned) {
      JupyterProject jp = new JupyterProject();
      jp.setCid(cid);
      jp.setPort(0);
      jp.setHdfsUserId(-1);
      allNotebooks.add(jp);
    }
    file.deleteOnExit();

    return allNotebooks;
  }
  
  @Override
  public String getJupyterHost() {
    return jupyterHost;
  }
  
  private int executeJupyterCommand(String... args) {
    if (args == null || args.length == 0) {
      return -99;
    }
    int exitValue;
    Integer id = 1;
    String prog = settings.getSudoersDir() + "/jupyter.sh";
    
    ProcessDescriptor.Builder pdBuilder = new ProcessDescriptor.Builder()
        .addCommand("/usr/bin/sudo")
        .addCommand(prog);
    for (String arg : args) {
      pdBuilder.addCommand(arg);
    }
    pdBuilder.setWaitTimeout(20L, TimeUnit.SECONDS);
    if (!LOGGER.isLoggable(Level.FINE)) {
      pdBuilder.ignoreOutErrStreams(true);
    }
    
    try {
      ProcessResult processResult = osProcessExecutor.execute(pdBuilder.build());
      LOGGER.log(Level.FINE, processResult.getStdout());
      exitValue = processResult.getExitCode();
    } catch (IOException ex) {
      LOGGER.log(Level.SEVERE,
          "Problem checking if Jupyter Notebook server is running: {0}", ex);
      exitValue = -2;
    }
    return exitValue;
  }
}
