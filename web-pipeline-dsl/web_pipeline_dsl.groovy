def branchName = "master"
def releasePraqmaCredentials = 'github'
def dockerHostLabel = 'docker && !utility-slave'
def jobPrefix = ''
def isJobDisabled = false
def jekyllTag = '0.2'

//##########################################WEBSITE CONFIGURATION##########################################
def readyBranch = 'origin/ready/**'
def udate = new Date()

def descriptionHtml = """
<h3>Auto generated by JobDSL plugin</h3>
<p>Updated ${udate}</p>
"""

//Read from config
def webconfig = new ConfigSlurper().parse(readFileFromWorkspace("web-pipeline-dsl/webconfig.groovy"))

def Closure configureSlack() {
  return {
    it / 'publishers' << 'jenkins.plugins.slack.SlackNotifier' {
      teamDomain('praqma')
      baseUrl()
      authTokenCredentialId('praqma-slack')
      room('#jenkins-noise')
      startNotification(true)
      notifySuccess(true)
      notifyAborted(false)
      notifyUnstable(true)
      notifyFailure(true)
      notifyBackToNormal(false)
      includeTestSummary(false)
      includeFailedTests(false)
      commitInfoChoice('AUTHORS_AND_TITLES')
      includeCustomMessage(false)
    }
  }
}

//The 'integrate' job is the one that has to pass the tollgate criteria. For websites this is: jekyll build
//We're enabling pretested integration for this part of the pipeline.
//TODO: Currently i have an issue with the docker image not picking up the correct locale when the slave is connected using ssh.
//Ideally this should just spawn a slave just like the rest of the jobs. Instead it just uses do
webconfig.each { site, config ->
  job("${jobPrefix}Web_${site}-integrate") {
    disabled(isJobDisabled)
    label(dockerHostLabel)
	  logRotator(-1,10)

    wrappers {
      timestamps()
    }

    triggers {
      scm("H/2 * * * *")
    }

    scm {
      git {
        remote {
          url(config.github)
          credentials(releasePraqmaCredentials)
        }
        branch(readyBranch)
        extensions {
          cleanBeforeCheckout()
          pretestedIntegration("SQUASHED", config.integrationbranch, "origin")
          pruneBranches()
        }
      }
    }

    steps {
      shell('''git log \
--decorate --oneline --graph ''' + config.integrationbranch + '''..${GIT_BRANCH} | tee git_graph.txt
export GIT_AUTHOR_COMMITTER=`git log --pretty=format:"%ae" -1`
env | grep -e '^GIT' > git.env''')
      environmentVariables {
        propertiesFile('git.env')
      }
      shell('''docker run \
--rm \
-u $(id -u):$(id -g) \
-v $(pwd):/srv/jekyll \
-w /srv/jekyll \
praqma/jekyll:''' + jekyllTag + ''' ./build.sh 2>&1 | tee jekyll_build.txt''')
    }

    publishers {
      archiveArtifacts('_site/**')
      textFinder(/ Error: |Warning: |Liquid Exception: /, ''  , true, false, true )
      pretestedIntegration()
      extendedEmail {
        triggers {
          failure {
            attachBuildLog(true)
            attachmentPatterns('*.txt')
            recipientList('${GIT_AUTHOR_COMMITTER}')
          }
          unstable {
            attachBuildLog(true)
            attachmentPatterns('*.txt')
            recipientList('${GIT_AUTHOR_COMMITTER}')
          }
        }
      }
      downstreamParameterized {
        trigger("${jobPrefix}Web_${site}-trigger") {
          condition('SUCCESS')
          triggerWithNoParameters(true)
          parameters {
              currentBuild()
          }
        }
      }
    }

    configure configureSlack()
  }

  job("${jobPrefix}Web_${site}-image-size-checker") {
    disabled(isJobDisabled)
    label(dockerHostLabel)
	  logRotator(-1,10)
    description(descriptionHtml)
    wrappers {
      timestamps()
    }

    scm {
      git {

        remote {
          url(config.github)
          credentials(releasePraqmaCredentials)
        }

        branch(config.integrationbranch)
        extensions {
          cleanBeforeCheckout()
        }
      }
    }

    steps {
      shell("""
docker run --rm -v \$(pwd):/site praqma/image-size-checker:1.8 imagecheck --resolution=1920x1080 --target=/site
      """)
    }

    publishers {
      textFinder(/Error:/, ''  , true, false, true )
    }
  }

  //TRIGGER JOBS
  job("${jobPrefix}Web_${site}-trigger") {
    disabled(isJobDisabled)
    logRotator(-1,10)
    wrappers {
      timestamps()
    }
    scm {
      git {

        remote {
          url(config.github)
          credentials(releasePraqmaCredentials)
        }

        branch(config.integrationbranch)
        extensions {
          cleanBeforeCheckout()
        }
      }
    }

    steps {
      shell('''#!/usr/bin/env bash -x
git branch -a

export GIT_STABLE_BRANCH=$(git branch -a | grep -q -e "remotes/origin/stable$" && echo stable )

if [ "${GIT_STABLE_BRANCH}x" == "x" ] ; then
  export GIT_STABLE_BRANCH=''' + config.integrationbranch + '''
fi

git log \\
   --decorate \\
   --oneline \\
   --graph \\
   --all \\
   -20 \\
   2>&1 | tee git_graph.txt

_GIT_AUTHOR_COMMITTER=`git log --pretty=format:"%ae" origin/${GIT_STABLE_BRANCH}..HEAD `
if [ "${_GIT_AUTHOR_COMMITTER}x" == "x" ] && [ "${GIT_PREVIOUS_SUCCESSFUL_COMMIT}x" != "${GIT_COMMIT}x" ]; then
  _GIT_AUTHOR_COMMITTER=`git log --pretty=format:"%ae" origin/${GIT_PREVIOUS_SUCCESSFUL_COMMIT}..HEAD`
fi
if [ "${_GIT_AUTHOR_COMMITTER}x" == "x" ]; then
  _GIT_AUTHOR_COMMITTER=`git log --pretty=format:"%ae" -1`
fi

export GIT_AUTHOR_COMMITTER=`echo "${_GIT_AUTHOR_COMMITTER}" | sed -e 's/ /,/g'`

env | grep -e '^GIT' > git.env

cat git.env
''')
      environmentVariables {
        propertiesFile('git.env')
      }
      downstreamParameterized {
        trigger(["${jobPrefix}Web_${site}-linkcheck",
                 "${jobPrefix}Web_${site}-resource-analysis",
                 "${jobPrefix}Web_${site}-image-size-checker"]) {
          block {
            buildStepFailure('FAILURE')
            failure('FAILURE')
            unstable('UNSTABLE')
          }
          parameters{
            gitRevision(true)
          }
        }
      }
    }

    publishers {
      git {
        pushOnlyIfSuccess()
        branch('origin', '${GIT_STABLE_BRANCH}')
      }
      archiveArtifacts('git.env','git_graph.txt')
      extendedEmail {
        triggers {
          failure {
            attachBuildLog(true)
            attachmentPatterns('git_graph.txt')
            recipientList('${GIT_AUTHOR_COMMITTER}')
          }
          unstable {
            attachBuildLog(true)
            attachmentPatterns('git_graph.txt')
            recipientList('${GIT_AUTHOR_COMMITTER}')
          }
        }
      }
    }
  }

  //The linkchecker job should run the linkchecker command and produce a set of parsable report files
  job("${jobPrefix}Web_${site}-linkcheck") {
    disabled(isJobDisabled)
    label(dockerHostLabel)
	  logRotator(-1,10)
    description(descriptionHtml)
    wrappers {
      timestamps()
    }

    scm {
      git {

        remote {
          url(config.github)
          credentials(releasePraqmaCredentials)
        }

        branch(config.integrationbranch)
        extensions {
          cleanBeforeCheckout()
        }
      }
    }

    steps {
      shell("""sleep 60
docker run --rm -v \$(pwd):/home/jenkins -w /home/jenkins -u jenkins praqma/linkchecker linkchecker \\
     \$(test -e linkchecker_ignore_urls.txt && grep '^--ignore-url' linkchecker_ignore_urls.txt) \\
     --user-agent='Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:21.0) Gecko/20100101 Firefox/21.0' \\
     --ignore-url=^tel: \\
     -o text -Fcsv/linkchecker.report.csv \\
     -Fhtml/linkchecker.report.html \\
     --complete \\
     http://${site} \\
     > linkchecker.log 2>&1 \\
     || echo 'INFO: Warnings and/or errors detected - needs interpretation'
""")
    }

    publishers {
      warnings(null,['LinkChecker CSV (Jekyll flavor)':'linkchecker.report.csv'])

      analysisCollector() {
        warnings()
      }

      publishHtml {
        report('.') {
          reportName('Linkchecker report')
          reportFiles('linkchecker.report.html')
          alwaysLinkToLastBuild(true)
        }
      }
      archiveArtifacts('linkchecker*.*')

      textFinder(/ERROR: linkchecker issue\(s\) detected/, ''  , true, false, true )

      mailer('', false, false)

    }
  }
  //The resource analysis job.
  job("${jobPrefix}Web_${site}-resource-analysis") {
    disabled(isJobDisabled)
    label(dockerHostLabel)
	  logRotator(-1,25)
    wrappers {
      timestamps()
    }
    scm {
      git {

        remote {
          url(config.github)
          credentials(releasePraqmaCredentials)
        }

        branch(config.integrationbranch)
        extensions {
          cleanBeforeCheckout()
        }
      }
    }

    steps {
      copyArtifacts("${jobPrefix}Web_${site}-integrate") {
        includePatterns('_site/**')
        optional()
        buildSelector {
            upstreamBuild {
              allowUpstreamDependencies(true)
              fallbackToLastSuccessful(true)
          }
        }
      }
      shell('''docker run \
--rm \
--tty \
--volume $(pwd):/website:rw \
--workdir /website \
praqma/jekyll:''' + jekyllTag + ''' \
analyze \
--source _site \
--copies /opt/static-analysis/template_report_duplication_junit.xml \
--unused /opt/static-analysis/template_report_usage_junit.xml''')
    }
    publishers {

	    archiveXUnit {
  	    jUnit {
  		    pattern('analyzed_report_*.xml')
  		    failIfNotNew(false)
  	    }

        failedThresholds {
          unstableNew(0)
          unstable(0)
          failure(0)
          failureNew(0)
        }
	    }

      archiveArtifacts('analyzed_report_*.xml')
      extendedEmail {
        triggers {
          failure {
            attachBuildLog(true)
            attachmentPatterns('analyzed_report_*.xml')
            sendTo {
              developers()
              culprits()
            }
          }
        }
      }
    }
  }
}

//Create views
nestedView("${jobPrefix}Website_Pipelines") {
  views {
    webconfig.each { site, config ->
      delegate.buildPipelineView("${site}") {
        selectedJob("${jobPrefix}Web_${site}-integrate")
        displayedBuilds(10)
        title("${site}")
      }
    }
  }
}

listView("${jobPrefix}Website_Jobs") {
  columns {
    status()
    weather()
    name()
    lastSuccess()
    lastFailure()
    lastDuration()
  }
  jobs {
    regex("${jobPrefix}Web_.*")
  }
}

//#########################################################################################################
