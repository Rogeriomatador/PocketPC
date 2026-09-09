package dev.pocketpc.core.runtime

import android.content.Context
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class RuntimeProbeEvidenceState(val box64SmokePassed:Boolean,val wineSmokePassed:Boolean,val displayBridgeSmokePassed:Boolean=false,val d3d11SmokePassed:Boolean=false,val graphicsPresentationSmokePassed:Boolean=false,val windowsProcessSmokePassed:Boolean=false,val winsockSmokePassed:Boolean=false,val winmmAudioApiSmokePassed:Boolean=false,val rawInputApiSmokePassed:Boolean=false)
object GuestToolFingerprint { fun of(m:GuestToolManifest)=digestCanonical(buildString{appendLine(m.id);appendLine(m.version);appendLine(m.architecture);appendLine(m.executionMode);appendLine(m.sourceCommit);appendLine(m.license);appendLine(m.entrypoint);m.files.sortedBy{it.path}.forEach{appendLine("${it.path}|${it.bytes}|${it.sha256}|${it.executable}")}}) }
object WindowsRuntimeLayerFingerprint { fun of(m:WindowsRuntimeLayerManifest)=digestCanonical(buildString{appendLine(m.id);appendLine(m.version);appendLine(m.sourceCommit);appendLine(m.license);appendLine(m.windowsArchitecture);appendLine(m.targetDirectory);m.files.sortedBy{it.destinationName}.forEach{appendLine("${it.path}|${it.destinationName}|${it.bytes}|${it.sha256}")}}) }
private fun digestCanonical(s:String)=Sha256.digest(ByteArrayInputStream(s.toByteArray(StandardCharsets.UTF_8))).sha256
class RuntimeProbeEvidenceStore(context:Context) {
 private val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
 fun stateFor(runtime:InstalledRuntime,tools:List<InstalledGuestTool>,layers:List<DeployedWindowsRuntimeLayer> = emptyList()):RuntimeProbeEvidenceState {
  val box=evidenceKey(runtime,tools,listOf("box64")); val full=RuntimeExecutionIdentity.of(runtime,tools,layers); val wine=evidenceKey(runtime,tools,listOf("box64","wine")); val d3d=graphicsKey(runtime,tools,layers,"dxvk")
  fun match(k:String,v:String?)=v!=null&&prefs.getString(k,null)==v
  return RuntimeProbeEvidenceState(match(KEY_BOX64,box),match(KEY_WINE,wine),match(KEY_DISPLAY_BRIDGE,full),match(KEY_D3D11,d3d),match(KEY_D3D11_PRESENT,d3d),match(KEY_WINDOWS_PROCESS,wine),match(KEY_WINSOCK,wine),match(KEY_WINMM_AUDIO,wine),match(KEY_RAW_INPUT,wine))
 }
 fun recordIfValid(probe:GuestRuntimeProbe,result:ProotExecutionResult,runtime:InstalledRuntime,tools:List<InstalledGuestTool>,layers:List<DeployedWindowsRuntimeLayer> = emptyList()):Boolean {
  if(!result.passed||result.outputTruncated)return false
  fun both(a:String,b:String)=result.output.contains(a)&&result.output.contains(b)
  val pair=when(probe){
   GuestRuntimeProbe.BOX64_SMOKE->if(both("POCKETPC_BOX64_SMOKE_OK","box64_x86_64_smoke=passed"))KEY_BOX64 to evidenceKey(runtime,tools,listOf("box64")) else null
   GuestRuntimeProbe.DISPLAY_BRIDGE_SMOKE->if(listOf("POCKETPC_DISPLAY_BRIDGE_SMOKE_OK","display_bridge_smoke=passed","POCKETPC_DISPLAY_BRIDGE_HOST_AUTH_OK","POCKETPC_DISPLAY_BRIDGE_WINDOW_OK","POCKETPC_DISPLAY_BRIDGE_POINTER_OK","POCKETPC_DISPLAY_BRIDGE_KEY_OK","POCKETPC_DISPLAY_BRIDGE_FRAME_WRITTEN_OK","POCKETPC_DISPLAY_BRIDGE_FRAME_ACK_OK","POCKETPC_DISPLAY_BRIDGE_HOST_FRAMEBUFFER_OK","POCKETPC_DISPLAY_BRIDGE_HOST_ROUNDTRIP_OK").all{result.output.contains(it)}) KEY_DISPLAY_BRIDGE to RuntimeExecutionIdentity.of(runtime,tools,layers) else null
   GuestRuntimeProbe.WINE_SMOKE->if(both("POCKETPC_WIN64_SMOKE_OK","wine_win64_smoke=passed"))KEY_WINE to evidenceKey(runtime,tools,listOf("box64","wine")) else null
   GuestRuntimeProbe.D3D11_SMOKE->if(both("POCKETPC_D3D11_SMOKE_OK","d3d11_dxvk_smoke=passed"))KEY_D3D11 to graphicsKey(runtime,tools,layers,"dxvk") else null
   GuestRuntimeProbe.D3D11_PRESENT_SMOKE->if(both("POCKETPC_D3D11_PRESENT_SMOKE_OK","d3d11_present_smoke=passed"))KEY_D3D11_PRESENT to graphicsKey(runtime,tools,layers,"dxvk") else null
   GuestRuntimeProbe.WINDOWS_PROCESS_SMOKE->if(both("POCKETPC_WIN_PROCESS_IPC_SMOKE_OK","windows_process_ipc_smoke=passed"))KEY_WINDOWS_PROCESS to evidenceKey(runtime,tools,listOf("box64","wine")) else null
   GuestRuntimeProbe.WINSOCK_SMOKE->if(both("POCKETPC_WINSOCK_SMOKE_OK","winsock_smoke=passed"))KEY_WINSOCK to evidenceKey(runtime,tools,listOf("box64","wine")) else null
   GuestRuntimeProbe.WINMM_AUDIO_API_SMOKE->if(both("POCKETPC_WINMM_AUDIO_API_OK","winmm_audio_api_smoke=passed"))KEY_WINMM_AUDIO to evidenceKey(runtime,tools,listOf("box64","wine")) else null
   GuestRuntimeProbe.RAW_INPUT_API_SMOKE->if(both("POCKETPC_RAW_INPUT_API_OK","raw_input_api_smoke=passed"))KEY_RAW_INPUT to evidenceKey(runtime,tools,listOf("box64","wine")) else null
   GuestRuntimeProbe.SHELL,GuestRuntimeProbe.ROOTFS,GuestRuntimeProbe.TOOLCHAIN->null
  }?:return false
  val value=pair.second?:return false; return prefs.edit().putString(pair.first,value).commit()
 }
 private fun evidenceKey(runtime:InstalledRuntime,tools:List<InstalledGuestTool>,ids:List<String>):String? { val by=tools.associateBy{it.manifest.id}; val selected=ids.map{by[it]?:return null}; return digestCanonical(buildString{appendLine(runtime.manifest.rootfsSha256.lowercase());appendLine(runtime.manifest.id);appendLine(runtime.manifest.version);selected.forEach{appendLine("${it.manifest.id}=${GuestToolFingerprint.of(it.manifest)}")}}) }
 private fun graphicsKey(runtime:InstalledRuntime,tools:List<InstalledGuestTool>,layers:List<DeployedWindowsRuntimeLayer>,id:String):String? { val base=evidenceKey(runtime,tools,listOf("box64","wine"))?:return null; val layer=layers.singleOrNull{it.manifest.id==id}?:return null; return digestCanonical("$base\n${layer.manifest.id}=${WindowsRuntimeLayerFingerprint.of(layer.manifest)}\n") }
 companion object { private const val PREFS="runtime-probe-evidence-v4";private const val KEY_BOX64="box64-smoke-key";private const val KEY_DISPLAY_BRIDGE="display-bridge-smoke-key";private const val KEY_WINE="wine-smoke-key";private const val KEY_D3D11="d3d11-smoke-key";private const val KEY_D3D11_PRESENT="d3d11-present-smoke-key";private const val KEY_WINDOWS_PROCESS="windows-process-ipc-smoke-key";private const val KEY_WINSOCK="winsock-smoke-key";private const val KEY_WINMM_AUDIO="winmm-audio-api-smoke-key";private const val KEY_RAW_INPUT="raw-input-api-smoke-key" }
}
