package rundeck.services

import com.dtolabs.rundeck.core.data.BaseDataContext
import com.dtolabs.rundeck.core.execution.ExecutionListener
import com.dtolabs.rundeck.core.execution.workflow.StepExecutionContext
import com.dtolabs.rundeck.core.plugins.PluggableProviderService
import com.dtolabs.rundeck.core.plugins.ValidatedPlugin
import com.dtolabs.rundeck.core.plugins.configuration.PropertyResolver
import com.dtolabs.rundeck.core.plugins.configuration.Validator
import com.dtolabs.rundeck.plugins.file.FileUploadPlugin
import com.dtolabs.rundeck.core.plugins.ConfiguredPlugin
import com.dtolabs.rundeck.server.plugins.RundeckPluginRegistry
import grails.testing.gorm.DataTest
import grails.testing.services.ServiceUnitTest
import rundeck.CommandExec
import rundeck.Execution
import rundeck.JobFileRecord
import rundeck.Option
import rundeck.ScheduledExecution
import rundeck.Workflow
import rundeck.services.events.ExecutionCompleteEvent
import rundeck.services.feature.FeatureService
import spock.lang.Specification
import spock.lang.Unroll

/**
 * See the API for {@link grails.test.mixin.services.ServiceUnitTestMixin} for usage instructions
 */

class FileUploadServiceSpec extends Specification implements ServiceUnitTest<FileUploadService>, DataTest {

    def setupSpec() { mockDomains JobFileRecord, Execution, ScheduledExecution, Workflow, Option, CommandExec }

    void "create record"() {
        given:
        UUID uuid = UUID.randomUUID()
        String jobid = 'abjobid'
        String user = 'auser'
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
            getLong('fileUploadService.tempfile.expiration', _) >> delay
        }
        service.frameworkService=Mock(FrameworkService){

        }
        Date expiryStart = new Date()
        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'z'*64
        String project = 'testproj'
        when:
        def result = service.createRecord(
                'abcd',
                123,
                uuid,
                sha,
                origName,
                jobid,
                optionName,
                user,
                project,
                expiryStart
        )
        then:
        result.storageReference == 'abcd'
        result.uuid == uuid.toString()
        result.jobId == jobid
        result.user == user
        result.size == 123L
        result.recordType == FileUploadService.RECORD_TYPE_OPTION_INPUT
        result.recordName == 'myopt'
        result.expirationDate == new Date(expiryStart.time + delay)
        result.execution == null
        result.fileState == 'temp'
        result.storageType == 'filesystem-temp'
        result.fileName == 'afile'
        result.project == project

        where:
        delay  | _
        0l     | _
        30000l | _
    }

    def "attach file for execution"() {
        given:
        UUID uuid = UUID.randomUUID()
        String ref = uuid.toString()
        String jobid = 'abjobid'
        String user = 'auser'
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
            getLong('fileUploadService.tempfile.expiration', _) >> 30000L
        }
        service.frameworkService = Mock(FrameworkService) {

        }
        service.pluginService = Mock(PluginService)
        service.taskService = Mock(TaskService)

        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: JobFileRecord.STATE_TEMP,
                uuid: uuid.toString(),
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: 'abcd',
                project: 'testproj'
        ).save()

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        Execution exec = mkexec(job)
        exec.validate()

        when:
        def result = service.attachFileForExecution(ref, exec, optionName)
        then:
        result != null
        result.id == jfr.id
        result.stateIsRetained()
        result.execution == exec


    }

    def "loadFileOptionInputs"() {
        given:
        UUID uuid = UUID.randomUUID()
        String ref = uuid.toString()
        String storageRef = 'astorageref'
        String jobid = 'abjobid'
        String user = 'auser'
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
            getLong('fileUploadService.tempfile.expiration', _) >> 30000L
        }
        def rundeckPluginRegistry = Mock(RundeckPluginRegistry) {
            createPluggableService(_) >> Mock(PluggableProviderService)
        }
        service.frameworkService = Mock(FrameworkService) {
            getRundeckPluginRegistry() >> rundeckPluginRegistry
        }
        service.pluginService = Mock(PluginService)
        service.taskService = Mock(TaskService)

        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: JobFileRecord.STATE_TEMP,
                uuid: uuid.toString(),
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: storageRef,
                project: 'testproj'
        ).save()

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        Execution exec = mkexec(job)
        exec.validate()
        StepExecutionContext context = Mock(StepExecutionContext)
        def plugin = Mock(FileUploadPlugin) {
            1 * hasFile(storageRef) >> true
            1 * retrieveLocalFile(storageRef) >> null
            1 * retrieveFile(storageRef, _) >> {
                it[1].write('abcd\n'.bytes)
                5L
            }
        }
        when:
        def result = service.loadFileOptionInputs(exec, job, context)
        then:
        result != null
        1 * service.getFrameworkService().getFrameworkPropertyResolver() >> Mock(PropertyResolver)
        1 * service.pluginService.configurePlugin('filesystem-temp', _, _,_) >>
                new ConfiguredPlugin<FileUploadPlugin>(plugin, null)
        1 * context.getDataContext() >> new BaseDataContext([option: [(optionName): ref]])
        1 * context.getExecutionListener() >> Mock(ExecutionListener)
        result[optionName] != null
        result[optionName + '.fileName'] != null
        result[optionName + '.sha'] == jfr.sha


    }


    def "attach file for execution incorrect option/job"() {
        given:
        UUID uuid = UUID.randomUUID()
        String ref = uuid.toString()
        String jobid = 'ajobid'
        String user = 'auser'
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
            getLong('fileUploadService.tempfile.expiration', _) >> 30000L
        }
        service.frameworkService = Mock(FrameworkService) {

        }
        service.pluginService = Mock(PluginService)

        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: JobFileRecord.STATE_TEMP,
                uuid: uuid.toString(),
                serverNodeUUID: null,
                sha: sha,
                jobId: inputjobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: 'abcd',
                project: 'testproj'
        ).save()

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        Execution exec = mkexec(job)
        exec.validate()

        when:
        def result = service.attachFileForExecution(ref, exec, inputoptname)
        then:
        FileUploadServiceException exc = thrown()
        exc.message.contains "File ref \"$uuid\" is not a valid for job $jobid, option $inputoptname"


        where:
        inputjobid   | inputoptname
        'wrongjobid' | 'myopt'
        'ajobid'     | 'wrongopt'
        'wronglgle'  | 'wrongopt'

    }

    @Unroll
    def "validate uuid for job option incorrect option/job"() {
        given:
        String jobid = 'ajobid'
        String user = 'auser'
        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: state,
                uuid: '44a26bb3-5013-4906-9997-286306005408',
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: 'abcd',
                project: 'testproj'
        ).save()

        ScheduledExecution job = mkjob(jobid)
        job.validate()

        when:
        def result = service.validateFileRefForJobOption(ref, inputjobid, inputoptname)
        then:
        result.valid == false
        result.error == errorCode
        result.args == errorArgs


        where:
        state     | ref                                    | inputjobid   | inputoptname | errorCode | errorArgs
        'deleted' | '44a26bb3-5013-4906-9997-286306005408' |
                'ajobid'                                                  |
                'myopt'                                                                  |
                'state'                                                                              |
                ['44a26bb3-5013-4906-9997-286306005408', 'deleted']
        'temp'    | 'wrong'                                |
                'blah'                                                    |
                'blah2'                                                                  |
                'notfound'                                                                           |
                ['wrong', 'blah', 'blah2']
        'temp'    | '44a26bb3-5013-4906-9997-286306005408' | 'wrongjobid' |
                'myopt'                                                                  |
                'invalid'                                                                            |
                ['44a26bb3-5013-4906-9997-286306005408', 'wrongjobid', 'myopt']
        'temp'    | '44a26bb3-5013-4906-9997-286306005408' | 'ajobid'     |
                'wrongopt'                                                               |
                'invalid'                                                                            |
                ['44a26bb3-5013-4906-9997-286306005408', 'ajobid', 'wrongopt']
        'temp'    | '44a26bb3-5013-4906-9997-286306005408' | 'wronglgle'  |
                'wrongopt'                                                               |
                'invalid'                                                                            |
                ['44a26bb3-5013-4906-9997-286306005408', 'wronglgle', 'wrongopt']

    }

    Execution mkexec(final ScheduledExecution scheduledExecution) {
        def exec = new Execution(
                user: "testuser", project: "testproj", loglevel: 'WARN',
                workflow: new Workflow(
                        commands: [new CommandExec(adhocExecution: true, adhocRemoteString: 'a remote string')]
                ).save(),
                scheduledExecution: scheduledExecution
        )
        exec.validate()
        if (exec.errors.hasErrors()) {
        }
        exec.save(flush: true)
    }

    ScheduledExecution mkjob(jobid) {
        new ScheduledExecution(
                uuid: jobid,
                jobName: 'monkey1', project: 'testproj', description: 'blah',
                workflow: new Workflow(
                        commands: [new CommandExec(adhocExecution: true, adhocRemoteString: 'a remote string')]
                ).save(),
                options: [
                        new Option(optionType: 'file', name: 'myopt', required: false, enforced: false).save()
                ]
        ).save(flush: true)
    }

    def "check and expire records"() {
        given:
        String jobid = 'ajobid'
        String user = 'auser'
        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def storageRef = 'abcd'
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: 'temp',
                uuid: '44a26bb3-5013-4906-9997-286306005408',
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: storageRef,
                project: 'testproj'
        ).save()

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        def rundeckPluginRegistry = Mock(RundeckPluginRegistry) {
            createPluggableService(_) >> Mock(PluggableProviderService)
        }
        service.frameworkService = Mock(FrameworkService) {
            getRundeckPluginRegistry() >> rundeckPluginRegistry
        }
        service.pluginService = Mock(PluginService)
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
        }
        def plugin = Mock(FileUploadPlugin) {
            1 * initialize()
            1 * transitionState(storageRef, FileUploadPlugin.ExternalState.Unused) >> stateResult
        }

        when:
        service.checkAndExpireAllRecords()
        then:
        1 * service.frameworkService.getServerUUID()
        1 * service.getFrameworkService().getFrameworkPropertyResolver() >> Mock(PropertyResolver)
        1 * service.pluginService.configurePlugin('filesystem-temp', _, _,_) >>
                new ConfiguredPlugin<FileUploadPlugin>(plugin, null)
        jfr.fileState == dbState

        where:
        stateResult                             | dbState
        FileUploadPlugin.InternalState.Deleted  | 'expired'
        FileUploadPlugin.InternalState.Retained | 'retained'
    }

    def "execution complete event"() {
        given:
        JobFileRecord.metaClass.static.withNewSession = {Closure c -> c.call() }
        String jobid = 'ajobid'
        String user = 'auser'
        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def storageRef = 'abcd'

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        Execution exec = mkexec(job)
        exec.validate()
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: 'retained',
                uuid: '44a26bb3-5013-4906-9997-286306005408',
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: storageRef,
                project: 'testproj',
                execution: exec
        ).save()

        def rundeckPluginRegistry = Mock(RundeckPluginRegistry) {
            createPluggableService(_) >> Mock(PluggableProviderService)
        }
        service.frameworkService = Mock(FrameworkService) {
            getRundeckPluginRegistry() >> rundeckPluginRegistry
        }
        service.pluginService = Mock(PluginService)
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
        }
        def plugin = Mock(FileUploadPlugin) {
            1 * initialize()
            1 * transitionState(storageRef, FileUploadPlugin.ExternalState.Used) >> stateResult
        }
        def event = new ExecutionCompleteEvent(execution: exec, job: job)
        when:
        service.executionComplete(event)
        then:
        1 * service.getFrameworkService().getFrameworkPropertyResolver() >> Mock(PropertyResolver)
        1 * service.pluginService.configurePlugin('filesystem-temp', _, _,_) >>
                new ConfiguredPlugin<FileUploadPlugin>(plugin, null)
        jfr.fileState == dbState

        where:
        stateResult                             | dbState
        FileUploadPlugin.InternalState.Deleted  | 'deleted'
        FileUploadPlugin.InternalState.Retained | 'retained'
    }

    def "execution complete event with retry feature enabled"() {
        given:
        JobFileRecord.metaClass.static.withNewSession = {Closure c -> c.call() }
        String jobid = 'ajobid'
        String user = 'auser'
        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def storageRef = 'abcd'

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        Execution exec = mkexec(job)
        exec.willRetry = true
        exec.validate()
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: 'retained',
                uuid: '44a26bb3-5013-4906-9997-286306005408',
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: storageRef,
                project: 'testproj',
                execution: exec
        ).save()

        def rundeckPluginRegistry = Mock(RundeckPluginRegistry) {
            createPluggableService(_) >> Mock(PluggableProviderService)
        }
        service.frameworkService = Mock(FrameworkService) {
            getRundeckPluginRegistry() >> rundeckPluginRegistry
        }
        service.pluginService = Mock(PluginService)
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
        }
        def event = new ExecutionCompleteEvent(execution: exec, job: job)
        when:
        service.executionComplete(event)
        then:
        0 * service.getFrameworkService().getFrameworkPropertyResolver()
        0 * service.pluginService.configurePlugin('filesystem-temp', _, _,_)
        jfr.fileState == 'retained'
    }

    @Unroll
    def "validate inuse file for execution"() {
        given:
        String jobid = 'ajobid'
        String user = 'auser'
        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        Execution exec = mkexec(job)
        exec.willRetry = willRetry
        exec.validate()

        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: 'retained',
                uuid: '44a26bb3-5013-4906-9997-286306005408',
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: 'abcd',
                project: 'testproj',
                execution: exec
        ).save()
        def ref = '44a26bb3-5013-4906-9997-286306005408'
        def inputjobid = 'ajobid'
        def inputoptname = 'myopt'
        when:
        def result = service.validateFileRefForJobOption(ref, inputjobid, inputoptname)
        then:
        result.valid == valid
        result.error == error
        where:
        willRetry | valid | error
        false     | false | 'inuse'
        true      | true  | null
    }

    def "attach file for scheduled execution"() {
        given:
        UUID uuid = UUID.randomUUID()
        String ref = uuid.toString()
        String jobid = 'abjobid'
        String user = 'auser'
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
            getLong('fileUploadService.tempfile.expiration', _) >> 30000L
        }
        service.frameworkService = Mock(FrameworkService) {

        }
        service.pluginService = Mock(PluginService)
        service.taskService = Mock(TaskService)

        String origName = 'afile'
        String optionName = 'myopt'
        String sha = 'fc4b5fd6816f75a7c81fc8eaa9499d6a299bd803397166e8c4cf9280b801d62c'
        def jfr = new JobFileRecord(
                fileName: origName,
                size: 123,
                recordType: 'option',
                expirationDate: new Date(),
                fileState: JobFileRecord.STATE_TEMP,
                uuid: uuid.toString(),
                serverNodeUUID: null,
                sha: sha,
                jobId: jobid,
                recordName: optionName,
                storageType: 'filesystem-temp',
                user: user,
                storageReference: 'abcd',
                project: 'testproj'
        ).save()

        ScheduledExecution job = mkjob(jobid)
        job.validate()
        Execution exec = mkexec(job)
        exec.status='scheduled'
        exec.save()
        exec.validate()

        when:
        def result = service.attachFileForExecution(ref, exec, optionName)
        then:
        result
        result.fileName==origName
    }

    def "validate option with plugin disabled"(){

        given:
        def option = new Option(optionType: 'file', name: 'myopt', required: false, enforced: false).save()
        service.featureService = Mock(FeatureService){
            featurePresent("fileUploadPlugin")>>false
        }
        service.configurationService = Mock(ConfigurationService) {
            getString('fileupload.plugin.type', _) >> { it[1] }
            getLong('fileUploadService.tempfile.expiration', _) >> 30000L
        }
        service.pluginService = Mock(PluginService){
            validatePluginConfig(_, _,_) >> new ValidatedPlugin(valid: true, report: Validator.buildReport().build())
        }

        when:
        def result = service.validateFileOptConfig(option)

        then:
        option.errors.hasErrors() == true

    }

    def "reject upload files with invalid name"() {
        given:

        def fileUploadPluginMock = Mock(FileUploadPlugin) {
            uploadFile(_, _, _, _) >> "stubfileref"
        }

        def service = GroovySpy(FileUploadService) {
            getPlugin() >> fileUploadPluginMock
            createRecord(_, _, _, _, _, _, _, _, _, _) >> { args ->
                return new JobFileRecord(
                    fileName: args[4],
                    uuid: args[2].toString()
                )
            }
        }
        service.configurationService = Mock(ConfigurationService) {
            getString('fileUploadService.tempfile.maxsize', _) >> "200MB"
        }

        def file = new ByteArrayInputStream("example file contents".getBytes())
        def length = file.available()


        when:
        def result = service.receiveFile(
            file,
            length,
            "admin",
            origFilename,
            "inputnamefile031u4023480928",
            [:],
            "jobid",
            "testproject",
            null
        )

        then:
        def exc = thrown(expectedException)
        exc.message.contains "Illegal filename:"

        where:
        origFilename                | expectedException
        '<script>alert(1)</script>'         | FileUploadServiceException
        'Robert\'); DROP TABLE Students;--' | FileUploadServiceException
        'filename/with/dirs.txt'            | FileUploadServiceException
        'file with @'                       | FileUploadServiceException
        '{"json": "content"}'               | FileUploadServiceException
        'files with # in name.txt'          | FileUploadServiceException
        'files with @ in name.txt'          | FileUploadServiceException
        'files with = in name.txt'          | FileUploadServiceException
        'files with % in name.txt'          | FileUploadServiceException
        'files with & in name.txt'          | FileUploadServiceException
        'files with { in name.txt'          | FileUploadServiceException
        'files with } in name.txt'          | FileUploadServiceException
        'files with $ in name.txt'          | FileUploadServiceException
        'files with ! in name.txt'          | FileUploadServiceException
        'files with ` in name.txt'          | FileUploadServiceException
        'files with ? in name.txt'          | FileUploadServiceException
        'files with * in name.txt'          | FileUploadServiceException
        'files with < in name.txt'          | FileUploadServiceException
        'files with > in name.txt'          | FileUploadServiceException
        'files with | in name.txt'          | FileUploadServiceException
        'files with : in name.txt'          | FileUploadServiceException
        'files with ; in name.txt'          | FileUploadServiceException
        'files with \' in name.txt'         | FileUploadServiceException
        'files with " in name.txt'          | FileUploadServiceException
    }

    def "accept upload files with valid name"() {
        given:

        def fileUploadPluginMock = Mock(FileUploadPlugin) {
            uploadFile(_, _, _, _) >> "stubfileref"
        }

        def service = GroovySpy(FileUploadService) {
            getPlugin() >> fileUploadPluginMock
            createRecord(_, _, _, _, _, _, _, _, _, _) >> { args ->
                return new JobFileRecord(
                    fileName: args[4],
                    uuid: args[2].toString()
                )
            }
        }
        service.configurationService = Mock(ConfigurationService) {
            getString('fileUploadService.tempfile.maxsize', _) >> "200MB"
        }

        def file = new ByteArrayInputStream("example file contents".getBytes())
        def length = file.available()

        when:
        def result = service.receiveFile(
            file,
            length,
            "admin",
            origFilename,
            "inputnamefile031u4023480928",
            [:],
            "jobid",
            "testproject",
            null
        )

        then:
        (UUID.fromString(result) instanceof UUID) == expected

        where:
        origFilename                                | expected
        'hello_world.txt'                           | true
        'filename.html'                             | true
        'with-hyphens_underscore.txt'               | true
        'with spaces .txt'                          | true
        'status report last-ver (1.05)_abraxas.txt' | true
    }

}
