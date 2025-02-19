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

package io.hops.hopsworks.common.jobs.spark;

import com.google.common.base.Strings;
import com.logicalclocks.servicediscoverclient.exceptions.ServiceDiscoveryException;
import io.hops.hopsworks.common.hdfs.DistributedFileSystemOps;
import io.hops.hopsworks.common.hdfs.DistributedFsService;
import io.hops.hopsworks.common.hdfs.HdfsUsersController;
import io.hops.hopsworks.common.hdfs.UserGroupInformationService;
import io.hops.hopsworks.common.hosts.ServiceDiscoveryController;
import io.hops.hopsworks.common.jupyter.JupyterController;
import io.hops.hopsworks.common.kafka.KafkaBrokers;
import io.hops.hopsworks.common.serving.ServingConfig;
import io.hops.hopsworks.common.util.HopsUtils;
import io.hops.hopsworks.persistence.entity.jobs.configuration.history.JobState;
import io.hops.hopsworks.persistence.entity.jobs.history.Execution;
import io.hops.hopsworks.persistence.entity.jobs.configuration.ExperimentType;
import io.hops.hopsworks.persistence.entity.jobs.configuration.spark.SparkJobConfiguration;
import io.hops.hopsworks.persistence.entity.jobs.description.Jobs;
import io.hops.hopsworks.persistence.entity.project.Project;
import io.hops.hopsworks.persistence.entity.user.Users;
import io.hops.hopsworks.common.dao.user.activity.ActivityFacade;
import io.hops.hopsworks.persistence.entity.user.activity.ActivityFlag;
import io.hops.hopsworks.common.jobs.AsynchronousJobExecutor;
import io.hops.hopsworks.persistence.entity.jobs.configuration.JobType;
import io.hops.hopsworks.common.util.Settings;
import io.hops.hopsworks.exceptions.GenericException;
import io.hops.hopsworks.exceptions.JobException;
import io.hops.hopsworks.exceptions.ProjectException;
import io.hops.hopsworks.exceptions.ServiceException;
import io.hops.hopsworks.restutils.RESTCodes;
import org.apache.hadoop.security.UserGroupInformation;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Interaction point between the Spark front- and backend.
 * <p/>
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NEVER)
public class SparkController {

  private static final Logger LOGGER = Logger.getLogger(SparkController.class.getName());
  @EJB
  private JupyterController jupyterController;
  @EJB
  private AsynchronousJobExecutor submitter;
  @EJB
  private ActivityFacade activityFacade;
  @EJB
  private UserGroupInformationService ugiService;
  @EJB
  private HdfsUsersController hdfsUsersBean;
  @EJB
  private Settings settings;
  @EJB
  private DistributedFsService dfs;
  @EJB
  private KafkaBrokers kafkaBrokers;
  @EJB
  private ServiceDiscoveryController serviceDiscoveryController;
  @Inject
  private ServingConfig servingConfig;

  /**
   * Start the Spark job as the given user.
   * <p/>
   * @param job
   * @param user
   * @return
   * @throws IllegalStateException If Spark is not set up properly.
   * @throws IOException If starting the job fails.
   * Spark job.
   */
  public Execution startJob(final Jobs job, String args, final Users user)
    throws ServiceException, GenericException, JobException, ProjectException {
    //First: some parameter checking.
    sanityCheck(job, user);
    String username = hdfsUsersBean.getHdfsUserName(job.getProject(), user);

    SparkJobConfiguration sparkConfig = (SparkJobConfiguration)job.getJobConfig();
    String appPath = sparkConfig.getAppPath();

    if(job.getJobType().equals(JobType.PYSPARK)) {
      if (job.getProject().getPythonEnvironment() == null) {
        //Throw error in Hopsworks UI to notify user to enable Anaconda
        throw new JobException(RESTCodes.JobErrorCode.JOB_START_FAILED, Level.SEVERE,
            "PySpark job needs to have Python Anaconda environment enabled");
      }
    }

    SparkJob sparkjob = createSparkJob(username, job, user);
    Execution exec = sparkjob.requestExecutionId(args);
    if(job.getJobType().equals(JobType.PYSPARK) && appPath.endsWith(".ipynb")) {
      submitter.getExecutionFacade().updateState(exec, JobState.CONVERTING_NOTEBOOK);
      String pyAppPath = HopsUtils.prepJupyterNotebookConversion(exec, username, dfs);
      sparkConfig.setAppPath(pyAppPath);
      jupyterController.convertIPythonNotebook(username, appPath, job.getProject(), pyAppPath,
        JupyterController.NotebookConversion.PY);
    }

    submitter.startExecution(sparkjob, args);
    activityFacade.persistActivity(ActivityFacade.RAN_JOB + job.getName(), job.getProject(), user.asUser(),
      ActivityFlag.JOB);
    return exec;
  }
  
  private void sanityCheck(Jobs job, Users user) throws GenericException, ProjectException {
    if (job == null) {
      throw new IllegalArgumentException("Trying to start job but job is not provided");
    } else if (user == null) {
      throw new IllegalArgumentException("Trying to start job but user is not provided");
    } else if (job.getJobType() != JobType.SPARK && job.getJobType() != JobType.PYSPARK) {
      throw new IllegalArgumentException(
        "Job configuration is not a Spark job configuration. Type: " + job.getJobType());
    }
    SparkJobConfiguration jobConf = (SparkJobConfiguration) job.getJobConfig();
    if(jobConf == null) {
      throw new IllegalArgumentException("Trying to start job but JobConfiguration is null");
    }

    String path = jobConf.getAppPath();
    if (Strings.isNullOrEmpty(path) || !(path.endsWith(".jar") || path.endsWith(".py")
            || path.endsWith(".ipynb"))) {
      throw new IllegalArgumentException("Path does not point to a .jar, .py or .ipynb file.");
    }
  
    inspectDependencies(job.getProject(), user, (SparkJobConfiguration) job.getJobConfig());
    
  }
  
  public SparkJobConfiguration inspectProgram(SparkJobConfiguration existingConfig, String path,
                                              DistributedFileSystemOps udfso) throws JobException {
    SparkJobConfiguration sparkConfig = null;
    if(existingConfig == null) {
      sparkConfig = new SparkJobConfiguration();
    } else {
      sparkConfig = existingConfig;
    }
    //If the main program is in a jar, try to set main class from it
    if (path.endsWith(".jar")) {
      try (JarInputStream jis = new JarInputStream(udfso.open(path))) {
        Manifest mf = jis.getManifest();
        if (mf != null) {
          Attributes atts = mf.getMainAttributes();
          if (atts.containsKey(Attributes.Name.MAIN_CLASS)) {
            sparkConfig.setMainClass(atts.getValue(Attributes.Name.MAIN_CLASS));
          } else {
            sparkConfig.setMainClass(null);
          }
        }
      } catch (IOException ex) {
        throw new JobException(RESTCodes.JobErrorCode.JAR_INSPECTION_ERROR, Level.SEVERE,
          "Failed to inspect jar at:" + path, ex.getMessage(), ex);
      }
    } else {
      //This case is needed as users may want to run a PySpark job and have a project default config
      //In that case we should not override it and set the experimentType, only set it if no default exists
      if(existingConfig == null) {
        sparkConfig.setExperimentType(ExperimentType.EXPERIMENT);
      }
      sparkConfig.setMainClass(Settings.SPARK_PY_MAINCLASS);
    }
    sparkConfig.setAppPath(path);
    return sparkConfig;
  }
  
  public void inspectDependencies(Project project, Users user, SparkJobConfiguration jobConf)
    throws ProjectException, GenericException {
    DistributedFileSystemOps udfso = null;
    try {
      if (!Strings.isNullOrEmpty(jobConf.getArchives())
        || !Strings.isNullOrEmpty(jobConf.getFiles())
        || !Strings.isNullOrEmpty(jobConf.getJars())
        || !Strings.isNullOrEmpty(jobConf.getPyFiles())) {
        
        udfso = dfs.getDfsOps(hdfsUsersBean.getHdfsUserName(project, user));
        if (!Strings.isNullOrEmpty(jobConf.getArchives())) {
          for (String filePath : jobConf.getArchives().split(",")) {
            if (!Strings.isNullOrEmpty(filePath) && !udfso.exists(filePath)) {
              throw new ProjectException(RESTCodes.ProjectErrorCode.FILE_NOT_FOUND, Level.FINEST,
                "Attached archive does not exist: " + filePath);
            }
          }
        }
        if (!Strings.isNullOrEmpty(jobConf.getFiles())) {
          for (String filePath : jobConf.getFiles().split(",")) {
            if (!Strings.isNullOrEmpty(filePath) && !udfso.exists(filePath)) {
              throw new ProjectException(RESTCodes.ProjectErrorCode.FILE_NOT_FOUND, Level.FINEST,
                "Attached file does not exist: " + filePath);
            }
          }
        }
        if (!Strings.isNullOrEmpty(jobConf.getJars())) {
          for (String filePath : jobConf.getJars().split(",")) {
            if (!Strings.isNullOrEmpty(filePath) && !udfso.exists(filePath)) {
              throw new ProjectException(RESTCodes.ProjectErrorCode.FILE_NOT_FOUND, Level.FINEST,
                "Attached JAR file does not exist: " + filePath);
            }
          }
        }
        if (!Strings.isNullOrEmpty(jobConf.getPyFiles())) {
          for (String filePath : jobConf.getPyFiles().split(",")) {
            if (!Strings.isNullOrEmpty(filePath) && !udfso.exists(filePath)) {
              throw new ProjectException(RESTCodes.ProjectErrorCode.FILE_NOT_FOUND, Level.FINEST,
                "Attached Python file does not exist: " + filePath);
            }
          }
        }
      }
    } catch (IOException ex) {
      throw new GenericException(RESTCodes.GenericErrorCode.UNKNOWN_ERROR, Level.INFO, null, null, ex);
    } finally {
      if (udfso != null) {
        dfs.closeDfsClient(udfso);
      }
    }
  }

  @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
  private SparkJob createSparkJob(String username, Jobs job, Users user) throws JobException, GenericException,
          ServiceException {
    SparkJob sparkjob = null;
    try {
      // Set Hopsworks consul service domain, don't use the address, use the name
      String hopsworksRestEndpoint = "https://" + serviceDiscoveryController.
              constructServiceFQDNWithPort(ServiceDiscoveryController.HopsworksService.HOPSWORKS_APP);

      UserGroupInformation proxyUser = ugiService.getProxyUser(username);
      try {
        sparkjob = proxyUser.doAs((PrivilegedExceptionAction<SparkJob>) () ->
                new SparkJob(job, submitter, user, settings.getHadoopSymbolicLinkDir(),
                        hdfsUsersBean.getHdfsUserName(job.getProject(), user),
                        settings, kafkaBrokers.getKafkaBrokersString(), hopsworksRestEndpoint, servingConfig,
                        serviceDiscoveryController));
      } catch (InterruptedException ex) {
        LOGGER.log(Level.SEVERE, null, ex);
      }

    } catch (IOException ex) {
      throw new JobException(RESTCodes.JobErrorCode.PROXY_ERROR, Level.SEVERE,
              "job: " + job.getId() + ", user:" + user.getUsername(), ex.getMessage(), ex);
    } catch (ServiceDiscoveryException ex) {
      throw new ServiceException(RESTCodes.ServiceErrorCode.SERVICE_NOT_FOUND, Level.SEVERE,
              "job: " + job.getId() + ", user:" + user.getUsername(), ex.getMessage(), ex);
    }

    if (sparkjob == null) {
      throw new GenericException(RESTCodes.GenericErrorCode.UNKNOWN_ERROR, Level.WARNING,
              "Could not instantiate job with name: " + job.getName() + " and id: " + job.getId(),
              "sparkjob object was null");
    }

    return sparkjob;
  }
}
