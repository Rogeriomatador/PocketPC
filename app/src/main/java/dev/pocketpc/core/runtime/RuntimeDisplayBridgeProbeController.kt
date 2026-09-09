package dev.pocketpc.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class RuntimeDisplayBridgeProbeResult(val process:ProotExecutionResult,val bridgeAuthenticated:Boolean,val negotiatedCapabilities:Int,val bridgeError:String?)

class RuntimeDisplayBridgeProbeController(private val executionController:ProotExecutionController) {
    suspend fun execute(basePlan:ProotInvocationPlan,runtime:InstalledRuntime,tools:List<InstalledGuestTool>,layers:List<DeployedWindowsRuntimeLayer>,userApproved:Boolean,timeoutMillis:Long=15_000L):RuntimeDisplayBridgeProbeResult=coroutineScope {
        val structural=basePlan.blockers.filterNot{it==ProotExecutionController.EXECUTION_APPROVAL_BLOCKER}
        if(!userApproved||structural.isNotEmpty()||basePlan.argv.isEmpty()) {
            val p=executionController.executeOneShot(plan=basePlan,userApproved=userApproved,timeoutMillis=timeoutMillis)
            return@coroutineScope RuntimeDisplayBridgeProbeResult(p,false,0,"DISPLAY_BRIDGE_NOT_STARTED")
        }
        val identity=RuntimeExecutionIdentity.of(runtime,tools,layers)
        val session=RuntimeDisplayBridgeSessionFactory.create(identity)
        val env=LinkedHashMap(basePlan.environment).apply{putAll(session.environment())}
        val envErrors=RuntimeEnvironment.validate(env)
        if(envErrors.isNotEmpty()) {
            val p=ProotExecutionResult(state=ProotExecutionState.BLOCKED,started=false,exitCode=null,output="",outputTruncated=false,blockers=envErrors,error="DISPLAY_BRIDGE_ENVIRONMENT_INVALID")
            return@coroutineScope RuntimeDisplayBridgeProbeResult(p,false,0,p.error)
        }
        val plan=basePlan.copy(environment=env)
        val host=RuntimeDisplayBridgeHost(session)
        val accept=async(Dispatchers.IO){host.acceptAuthenticated()}
        val process=try{executionController.executeOneShot(plan=plan,userApproved=true,timeoutMillis=timeoutMillis)}finally{if(!accept.isCompleted)host.close()}
        val peerResult=runCatching{accept.await()}.getOrElse{Result.failure(it)}
        val peer=peerResult.getOrNull()
        var bridgeError=peerResult.exceptionOrNull()?.message
        var roundTrip=false
        if(peer!=null) {
            val machine=RuntimeDisplayBridgeStateMachine(peer.negotiatedCapabilities)
            val interaction=runCatching {
                machine.apply(peer.readFrame().getOrThrow()).getOrThrow()
                machine.apply(peer.readFrame().getOrThrow()).getOrThrow()
                require(machine.snapshot().singleOrNull()?.geometry?.visible==true){"DISPLAY_BRIDGE_WINDOW_NOT_VISIBLE"}
                peer.sendPointer(RuntimeBridgePointerEvent(1,1,100,80,1,0,0))
                peer.sendKey(RuntimeBridgeKeyEvent(1,1,65,30,0,0))
                peer.sendFramePresented(RuntimeBridgeFramePresented(1,1,0))
                machine.apply(peer.readFrame().getOrThrow()).getOrThrow()
                require(machine.snapshot().isEmpty()){"DISPLAY_BRIDGE_WINDOW_DESTROY_FAILED"}
            }
            roundTrip=interaction.isSuccess
            if(interaction.isFailure) bridgeError=interaction.exceptionOrNull()?.message
            peer.close()
        }
        host.close()
        val authenticated=peerResult.isSuccess
        val negotiated=peer?.negotiatedCapabilities?:0
        val annotated=if(authenticated&&roundTrip) process.copy(output=buildString{
            append(process.output);if(process.output.isNotEmpty()&&!process.output.endsWith("\n"))appendLine()
            appendLine("POCKETPC_DISPLAY_BRIDGE_HOST_AUTH_OK caps=$negotiated")
            appendLine("POCKETPC_DISPLAY_BRIDGE_HOST_WINDOW_OK")
            appendLine("POCKETPC_DISPLAY_BRIDGE_HOST_INPUT_OK")
            appendLine("POCKETPC_DISPLAY_BRIDGE_HOST_ROUNDTRIP_OK")
        }) else process.copy(error=listOfNotNull(process.error,bridgeError?.let{"DISPLAY_BRIDGE_HOST_FAILED:$it"}).joinToString(" | ").ifBlank{"DISPLAY_BRIDGE_HOST_FAILED"})
        RuntimeDisplayBridgeProbeResult(annotated,authenticated&&roundTrip,negotiated,bridgeError)
    }
}
