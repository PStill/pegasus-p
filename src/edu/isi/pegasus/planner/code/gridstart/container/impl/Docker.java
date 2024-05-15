/**
 * Copyright 2007-2017 University Of Southern California
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.isi.pegasus.planner.code.gridstart.container.impl;

import edu.isi.pegasus.planner.catalog.classes.Profiles;
import edu.isi.pegasus.planner.catalog.transformation.classes.Container;
import edu.isi.pegasus.planner.classes.ADag;
import edu.isi.pegasus.planner.classes.Job;
import edu.isi.pegasus.planner.classes.PegasusBag;
import edu.isi.pegasus.planner.namespace.Condor;
import edu.isi.pegasus.planner.namespace.ENV;
import edu.isi.pegasus.planner.namespace.Pegasus;

/**
 * An interface to determine how a job gets wrapped to be launched on various containers, as a
 * shell-script snippet that can be embedded in PegasusLite
 *
 * @author vahi
 */
public class Docker extends AbstractContainer {

    /** The directory in the container to be used as working directory */
    public static final String CONTAINER_WORKING_DIRECTORY = "/scratch";

    private static String WORKER_PACKAGE_SETUP_SNIPPET = null;

    /** time in seconds that we wait for before launching docker run command. */
    private static final String SLEEP_TIME_FOR_DOCKER_BOOTUP = "30";

    /** the variable that stores the path for the root user * */
    private static final String ROOT_PATH_VARIABLE_KEY = "root_path";

    /**
     * Initiailizes the Container shell wrapper
     *
     * @param bag @param dag
     */
    public void initialize(PegasusBag bag, ADag dag) {
        super.initialize(bag, dag);
    }

    /**
     * Creates the snippet for container initialization, in the script that is launched on the host
     * OS, by PegasusLite
     *
     * @param job
     * @return the bash snippet
     */
    @Override
    public StringBuilder containerInit(Job job) {
        StringBuilder sb = new StringBuilder();

        // sets up the variables used for docker run command
        // FIXME docker_init has to be passed the name of the tar file?
        Container c = job.getContainer();
        sb.append("docker_init").append(" ").append(c.getLFN());

        return sb;
    }

    /**
     * Constructs the snippet for executing the container, in the PegasusLite script launched on the
     * HOST OS
     *
     * @param job
     * @return the bash snippet
     */
    @Override
    public StringBuilder containerRun(Job job) {
        StringBuilder sb = new StringBuilder();
        Container c = job.getContainer();

        // we want to sleep for few seconds to allow the container to boot up fully
        // sb.append( "sleep" ).append( " " ).append( Docker.SLEEP_TIME_FOR_DOCKER_BOOTUP ).append(
        // "\n" );

        // assume docker is available in path
        sb.append(this.wrapContainerInvocationWithLauncher(job, "docker"));
        sb.append(" ").append("run").append(" ");
        sb.append("--user root ");

        // directory where job is run is mounted as scratch
        sb.append("-v $PWD:").append(CONTAINER_WORKING_DIRECTORY).append(" ");

        // PM-1621 add --gpus all option if user has gpus requested with the job
        if (job.vdsNS.containsKey(Pegasus.GPUS_KEY)
                || job.condorVariables.containsKey(Condor.REQUEST_GPUS_KEY)) {
            sb.append("--gpus all").append(" ");
        }

        // PM-1298 mount any host directories if specified
        for (Container.MountPoint mp : c.getMountPoints()) {
            sb.append("-v ").append(mp).append(" ");
        }

        sb.append("-w=").append(CONTAINER_WORKING_DIRECTORY).append(" ");

        // PM-1524 set entry point for the container to /bin/sh
        sb.append("--entrypoint /bin/sh").append(" ");

        // PM-1626 incorporate any user specified extra arguments
        String extraArgs = job.vdsNS.getStringValue(Pegasus.CONTAINER_ARGUMENTS_KEY);
        if (extraArgs != null) {
            sb.append(extraArgs);
            sb.append(" ");
        }

        sb.append("--name $cont_name ");
        sb.append(" $cont_image ");

        // track

        // invoke the command to run as user who launched the job
        sb.append("-c ")
                .append("\"")
                .append("set -e ;")
                .append("export ")
                .append(ROOT_PATH_VARIABLE_KEY)
                .append("=\\$PATH ;") // PM-1630 preserve the path for root user
                .append("if ! grep -q -E  \"^$cont_group:\" /etc/group ; then ")
                .append("groupadd -f --gid $cont_groupid $cont_group ;")
                .append("fi; ")
                .append("if ! id $cont_user 2>/dev/null >/dev/null; then ")
                .append("   if id $cont_userid 2>/dev/null >/dev/null; then ")
                // PM-1809 the userid already exists. let the container os decide userid
                .append("       useradd -o --uid $cont_userid --gid $cont_groupid $cont_user; ")
                .append("   else ")
                .append("       useradd --uid $cont_userid --gid $cont_groupid $cont_user; ")
                .append("   fi; ")
                .append("fi; ")
                .append("su $cont_user -c ");
        sb.append("\\\"");
        sb.append("./").append(this.getJobLaunchScriptName(job)).append(" ");
        sb.append("\\\"");

        sb.append("\"");
        return sb;
    }

    /**
     * Constructs the snippet for removing the container, in the PegasusLite script launched on the
     * HOST OS
     *
     * @param job
     * @return the bash snippet
     */
    @Override
    public StringBuilder containerRemove(Job job) {
        StringBuilder sb = new StringBuilder();

        // remove the docker container
        sb.append("docker rm --force $cont_name ").append(" 1>&2");

        return sb;
    }

    /**
     * Return the description
     *
     * @return
     */
    public String describe() {
        return "Docker";
    }

    /**
     * Construct the snippet that generates the shell script responsible for setting up the worker
     * package in the container.
     *
     * @return
     */
    @Override
    protected String constructContainerWorkerPackagePreamble() {
        // quasi singleton?
        if (WORKER_PACKAGE_SETUP_SNIPPET == null) {
            WORKER_PACKAGE_SETUP_SNIPPET = super.constructContainerWorkerPackagePreamble();
        }
        return WORKER_PACKAGE_SETUP_SNIPPET;
    }

    /**
     * Returns the bash snippet containing the environment variables to be set for a job inside the
     * container.This snippet is embedded in the <job>-cont.sh file that is written out in
     * PegasusLite on the worker dir, and is launched inside the container.
     *
     * @param job
     * @return the bash snippet
     */
    @Override
    public String constructJobEnvironmentInContainer(Job job) {
        StringBuilder sb = new StringBuilder();

        // set the job environment variables explicitly in the -cont.sh file
        sb.append("# setting environment variables for job").append('\n');
        sb.append(this.constructJobEnvironmentFromContainer(job.getContainer()));

        ENV containerENVProfiles =
                (ENV) job.getContainer().getProfilesObject().get(Profiles.NAMESPACES.env);
        boolean pathVariableSet = containerENVProfiles.containsKey("PATH");

        if (!pathVariableSet) {
            // PM-1630 special handling for PATH variable. if a user has not
            // associated a PATH variable with the job, we set the PATH to
            // root_path that stores the PATH set for the root user
            sb.append("export")
                    .append(" ")
                    .append("PATH")
                    .append("=")
                    .append("\\$")
                    .append(ROOT_PATH_VARIABLE_KEY);
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Return the directory inside the container where the user job is launched from
     *
     * @return String
     */
    @Override
    public String getContainerWorkingDirectory() {
        return Docker.CONTAINER_WORKING_DIRECTORY;
    }
}
