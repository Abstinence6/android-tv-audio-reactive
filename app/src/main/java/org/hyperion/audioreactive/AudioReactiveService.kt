package org.hyperion.audioreactive

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One user-approved projection owns only the sources required by the selected mode. */
class AudioReactiveService : Service() {
 companion object {
  const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_RESULT_DATA="resultData"; const val EXTRA_NO_INPUT_ANIMATION="noInputAnimation"; const val EXTRA_WLED_ROUTE_BINDING="wledRouteBinding"; const val EXTRA_HYPERION_ROUTE_BINDING="hyperionRouteBinding"; const val EXTRA_ADMISSION_GENERATION="admissionGeneration"; const val EXTRA_ADMISSION_FAILED="admissionFailed"; const val EXTRA_TRANSITION_EPOCH="transitionEpoch"; const val EXTRA_CAPABILITY_NONCE="transitionCapabilityNonce"; const val EXTRA_TARGET_RENDER="transitionTargetRender"; const val ACTION_LOCAL_TRANSITION="org.hyperion.audioreactive.LOCAL_TRANSITION"; const val ACTION_CAPTURE_STATE_CHANGED="org.hyperion.audioreactive.CAPTURE_STATE_CHANGED"; private const val CHANNEL="capture"; private const val ID=7
  internal const val STARTUP_FAILURE_DIAGNOSTIC="startup failed; local cleanup completed"
  internal const val CLEANUP_FAILURE_DIAGNOSTIC="capture stopped; local cleanup incomplete"
  private const val TAG="AudioReactiveService"
  @Volatile private var alive=false; @Volatile private var status=CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT
  fun exists()=alive; fun captureStatus()=status; fun stopExisting(context:android.content.Context){if(alive)context.stopService(Intent(context,AudioReactiveService::class.java))}
 }
 private val running=AtomicBoolean(); private val worker=Executors.newSingleThreadExecutor(); private var projection:MediaProjection?=null; private var recorder:AudioRecord?=null; private var reader:ImageReader?=null; private var display:android.hardware.display.VirtualDisplay?=null; private var router:OutputRouter?=null; private var admittedSettings:AudioSettings?=null; private var transitions:LocalTransitionPolicy?=null; private var invalidAdmissionGeneration:Long?=null; private var selectedVoiceInputDeviceId:Int?=null
 private val voiceInputDeviceCallback=object:AudioDeviceCallback(){override fun onAudioDevicesRemoved(removed:Array<out AudioDeviceInfo>){if(VoiceInputRoutePolicy.selectedDeviceWasRemoved(selectedVoiceInputDeviceId,removed.map{it.id}))terminateVoiceInputLost()}}
 private val voiceInputRouteListener=AudioRouting.OnRoutingChangedListener{routing->if(VoiceInputRoutePolicy.routedAwayFromSelectedDevice(selectedVoiceInputDeviceId,(routing as? AudioRecord)?.routedDevice?.id))terminateVoiceInputLost()}
 private val admission = ServiceRouteAdmission(::discardRouteBindings)
 private val lifecycle = CaptureServiceLifecycle(::performTeardown)
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action==ACTION_LOCAL_TRANSITION) { worker.execute { transition(intent) }; return START_NOT_STICKY }
  val generation=intent?.getLongExtra(EXTRA_ADMISSION_GENERATION,Long.MIN_VALUE)?:Long.MIN_VALUE
  val ids=RouteBindingIds(intent?.getStringExtra(EXTRA_WLED_ROUTE_BINDING),intent?.getStringExtra(EXTRA_HYPERION_ROUTE_BINDING))
  // The lifecycle gate linearizes route admission against onStop/onDestroy cancellation.
  if(!lifecycle.beginStart {
   if(!OutputDiagnosticAdmission.reserveCapture()) false
   else if(admission.reserve(ids)) true
   else { OutputDiagnosticAdmission.releaseCapture(); false }
  }) { admission.discardLifecycleRejectedStart(ids); return START_NOT_STICKY }
  val animation=intent?.getBooleanExtra(EXTRA_NO_INPUT_ANIMATION,false)==true
  val data=intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
  if(!animation && (intent?.getIntExtra(EXTRA_RESULT_CODE,0)!=Activity.RESULT_OK||data==null)){rejectInvalidStart(generation);return START_NOT_STICKY}
  val frozen=RuntimeSettings.snapshot()
  if(animation != frozen.isNoInputAnimation()){rejectInvalidStart(generation);return START_NOT_STICKY}
  val valid=if(frozen.outputMode==OutputMode.HYPERION)HyperionRouteBindings.has(ids.hyperion,frozen) else WledRouteBindings.has(ids.wled,frozen)
  if(!valid){rejectInvalidStart(generation);return START_NOT_STICKY}
  // A stop between validation and foreground startup cancels the pending binding instead.
  val foregroundTypes=if(animation) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if(frozen.requiresAudio()&&frozen.audioInput == AudioInput.MICROPHONE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
  if(!lifecycle.whileStarting { channel(); startForeground(ID,notification(animation),foregroundTypes) }) { admission.discardPending(); return START_NOT_STICKY }
  worker.execute { if(animation) startAnimation(frozen,generation) else start(requireNotNull(data),frozen,generation) }
  return START_NOT_STICKY
 }
 private fun discardRouteBindings(ids:RouteBindingIds){WledRouteBindings.discard(ids.wled);HyperionRouteBindings.discard(ids.hyperion)}
 private fun rejectInvalidStart(generation:Long){invalidAdmissionGeneration=generation;lifecycle.stop()}
 private fun start(data:Intent,s:AudioSettings,generation:Long){
  try {
   if(!lifecycle.whileStarting { status=CaptureStatus.PREPARING_PROJECTION; LocalStatusStore.reset(status); broadcast() }) return
   if(!lifecycle.acquire(
     acquire = { (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(Activity.RESULT_OK,data)?:error("projection") },
     release = { it.stop() },
     assign = { projection=it },
   )) return
   val p=projection?:return
   if(!lifecycle.whileStarting { p.registerCallback(projectionCallback(p),null) }) return
   if(s.requiresAudio()&&!lifecycle.acquire(acquire={createAudio(p,s)},release={it.stop();it.release()},assign={recorder=it})) return
   if(s.requiresVideo()&&!createVideoWhileStarting(p)) return
   if(!lifecycle.acquire(acquire={ admission.consume { ids -> OutputRouter.create(s,ids.wled,ids.hyperion) } },release={it.stop()},assign={router=it})) return
   if(!lifecycle.activate {
    alive=true; admittedSettings=s; transitions=LocalTransitionPolicy(s.renderMode); LiveRendererSettings.begin(s); broadcast(generation); running.set(true); router!!.start()
    status=when(s.renderMode){RenderMode.AUDIO->CaptureStatus.CAPTURE_ACTIVE_AUDIO;RenderMode.VIDEO->CaptureStatus.CAPTURE_ACTIVE_VIDEO;RenderMode.VIDEO_AUDIO->CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO;RenderMode.ANIMATION->CaptureStatus.CAPTURE_ACTIVE_ANIMATION}
    LocalStatusStore.update(LocalCaptureStatus(status, if(s.outputMode==OutputMode.WLED)s.selectedWledDevices().map{it.name}else listOf("Hyperion"),s.selectedWledDevices().filter{s.calibrationFor(it)?.validFor(it)==true}.map{it.name},s.selectedWledDevices().filterNot{WledCalibrationPolicy.routeable(s,it)}.map{it.name}))
    broadcast()
   }) return
   videoLoop(s)
  } catch(failure:Exception) {
   Log.w(TAG, "$STARTUP_FAILURE_DIAGNOSTIC (${failure.javaClass.simpleName})")
   status=CaptureStatus.ROUTER_INIT_FAILED;LocalStatusStore.reset(status);broadcastAdmissionFailed(generation);broadcast();stop()
  }
 }
 private fun startAnimation(s:AudioSettings,generation:Long){
  try {
   if(!lifecycle.whileStarting { status=CaptureStatus.PREPARING_ANIMATION; LocalStatusStore.reset(status); broadcast() }) return
   if(!lifecycle.acquire(acquire={ admission.consume { ids -> OutputRouter.create(s,ids.wled,ids.hyperion) } },release={it.stop()},assign={router=it})) return
   if(!lifecycle.activate { alive=true; admittedSettings=s; transitions=LocalTransitionPolicy(s.renderMode); LiveRendererSettings.begin(s); broadcast(generation); running.set(true); router!!.start(); status=CaptureStatus.CAPTURE_ACTIVE_ANIMATION; LocalStatusStore.reset(status); broadcast() }) return
   videoLoop(s)
  } catch(failure:Exception) { Log.w(TAG, "$STARTUP_FAILURE_DIAGNOSTIC (${failure.javaClass.simpleName})"); status=CaptureStatus.ROUTER_INIT_FAILED; LocalStatusStore.reset(status); broadcastAdmissionFailed(generation); broadcast(); stop() }
 }
 private fun createAudio(p:MediaProjection,s:AudioSettings):AudioRecord { if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)error("audio permission"); val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192); val builder=AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()).setBufferSizeInBytes(min); if(s.audioInput==AudioInput.MICROPHONE){val microphone=VoiceInputDevices.connected(this)?:error("microphone disconnected"); selectedVoiceInputDeviceId=microphone.id; val record=builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).build(); if(!record.setPreferredDevice(microphone)){record.release();selectedVoiceInputDeviceId=null;error("microphone route unavailable")}; record.addOnRoutingChangedListener(voiceInputRouteListener,null); (getSystemService(AUDIO_SERVICE)as AudioManager).registerAudioDeviceCallback(voiceInputDeviceCallback,null); record.startRecording(); return record}; return builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(p).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()).build().also{it.startRecording()} }
 private fun createVideoWhileStarting(p:MediaProjection):Boolean {
  if(!lifecycle.acquire(acquire={ImageReader.newInstance(320,180,android.graphics.PixelFormat.RGBA_8888,VideoLatencyPolicy.IMAGE_READER_MAX_IMAGES)},release={it.close()},assign={reader=it})) return false
  val captureReader=reader?:return false
  return lifecycle.acquire(acquire={p.createVirtualDisplay("audio-reactive-video",320,180,1,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,captureReader.surface,null,null)},release={it?.release()},assign={display=it})
 }
 private fun createVideo(p:MediaProjection):Boolean {
  if(reader==null) reader=ImageReader.newInstance(320,180,android.graphics.PixelFormat.RGBA_8888,VideoLatencyPolicy.IMAGE_READER_MAX_IMAGES)
  if(display==null) display=p.createVirtualDisplay("audio-reactive-video",320,180,1,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,requireNotNull(reader).surface,null,null)
  return true
 }
 /** All source changes are local-capability-gated and serialized with stop/revocation. */
 private fun transition(intent:Intent) {
  val target=runCatching { RenderMode.valueOf(intent.getStringExtra(EXTRA_TARGET_RENDER) ?: "") }.getOrNull() ?: return
  val epoch=intent.getLongExtra(EXTRA_TRANSITION_EPOCH,Long.MIN_VALUE)
  val nonce=intent.getStringExtra(EXTRA_CAPABILITY_NONCE) ?: return
  val data=intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
  val result=intent.getIntExtra(EXTRA_RESULT_CODE,0)==Activity.RESULT_OK && data!=null
  lifecycle.whileActive {
   val gate=transitions ?: return@whileActive
   if(!LocalTransitionCapabilities.consume(epoch,nonce)) return@whileActive
   gate.mint(epoch,nonce)
   if(gate.decide(LocalTransitionRequest(epoch,nonce,target,result)) !is TransitionDecision.Accept) return@whileActive
   try {
    val current = gate.current()
    if (!AudioSourceAdmissionPolicy.permits(requireNotNull(admittedSettings).audioInput, current, target, checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) return@whileActive
    val needs=RenderRequirements.forMode(target)
    var p=projection
    if(needs.video || needs.audio) {
     if(p==null) { p=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(Activity.RESULT_OK,requireNotNull(data))?:error("projection"); projection=p; p!!.registerCallback(projectionCallback(p!!),null) }
     if(needs.audio && recorder==null) recorder=createAudio(p!!,requireNotNull(admittedSettings))
     if(needs.video && reader==null && !createVideo(p!!)) error("video source unavailable")
    }
    if(!needs.audio) releaseAudio()
    if(!needs.video) releaseVideo()
    // Playback capture remains projection-backed in AUDIO mode; only ANIMATION releases it.
    if(!ProjectionOwnershipPolicy.retainsProjection(needs)) {
     projection?.let { owned ->
      // The projection callback is serialized with this gate and acknowledges this exact release.
      if(lifecycle.releaseProjection(owned) { owned.stop() }) projection=null
     }
    }
    setForegroundTypesFor(needs)
    gate.commit(target)
    LiveRendererSettings.commitRenderMode(target)
    status=when(target){RenderMode.AUDIO->CaptureStatus.CAPTURE_ACTIVE_AUDIO;RenderMode.VIDEO->CaptureStatus.CAPTURE_ACTIVE_VIDEO;RenderMode.VIDEO_AUDIO->CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO;RenderMode.ANIMATION->CaptureStatus.CAPTURE_ACTIVE_ANIMATION}
    LocalStatusStore.update(LocalStatusStore.snapshot().copy(captureStatus=status)); broadcast()
   } catch(_:Exception) { lifecycle.stop { status=CaptureStatus.ROUTER_INIT_FAILED; router?.stop() } }
  }
 }
 private fun setForegroundTypesFor(needs:RenderRequirements) {
  val types=if(!needs.audio && !needs.video) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if(needs.audio && admittedSettings?.audioInput==AudioInput.MICROPHONE) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
  startForeground(ID,notification(!needs.audio&&!needs.video),types)
 }
 private fun projectionCallback(owned:MediaProjection)=object:MediaProjection.Callback(){override fun onStop(){
  lifecycle.onProjectionStopped(owned) { stop() }
 }}
 private fun reconcileSources(p:MediaProjection,s:AudioSettings):Boolean = lifecycle.whileActive {
  if(s.requiresAudio()&&recorder==null) {
   if(s.audioInput==AudioInput.MICROPHONE) startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
   recorder=createAudio(p,s)
  }
  if(!s.requiresAudio()&&recorder!=null) releaseAudio()
  if(s.requiresVideo()&&reader==null&&!createVideo(p)) error("video source unavailable")
  if(!s.requiresVideo()&&reader!=null) releaseVideo()
  val next=when(s.renderMode){RenderMode.AUDIO->CaptureStatus.CAPTURE_ACTIVE_AUDIO;RenderMode.VIDEO->CaptureStatus.CAPTURE_ACTIVE_VIDEO;RenderMode.VIDEO_AUDIO->CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO;RenderMode.ANIMATION->CaptureStatus.CAPTURE_ACTIVE_ANIMATION}
  if(status!=next){status=next;LocalStatusStore.update(LocalStatusStore.snapshot().copy(captureStatus=next));broadcast()}
 }
 private fun releaseAudio(){
  val current=recorder?:return
  if(selectedVoiceInputDeviceId!=null){runCatching{(getSystemService(AUDIO_SERVICE)as AudioManager).unregisterAudioDeviceCallback(voiceInputDeviceCallback)};runCatching{current.removeOnRoutingChangedListener(voiceInputRouteListener)};selectedVoiceInputDeviceId=null}
  runCatching{current.stop()};runCatching{current.release()};recorder=null
  // Drop the microphone FGS type as soon as a live VIDEO transition releases the microphone source.
  runCatching{startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)}
 }
 private fun releaseVideo(){runCatching{display?.release()};display=null;runCatching{reader?.close()};reader=null}
 private fun videoLoop(initial:AudioSettings){val frameSpec=initial.liveCaptureFrame();val processor=VideoFrameProcessor(frameSpec.width,frameSpec.height);val audioRenderer=AudioToFullFrameRenderer(frameSpec);val animationRenderer=AnimationFrameRenderer(frameSpec);val smoother=RgbFrameSmoother(frameSpec.bytes);val samples=ShortArray(CaptureCadence.ANALYSIS_SAMPLES*2);val latestSamples=ShortArray(CaptureCadence.ANALYSIS_SAMPLES*2);val analyzer=PcmAnalyzer();val silence=AudioFeatures(0f,0f,0f,0f,0f,0f,FloatArray(AudioFeatures.BAND_COUNT),false);var features:AudioFeatures?=null;var frames=0L;val began=System.nanoTime();while(running.get()){val started=System.nanoTime();val s=LiveRendererSettings.apply(RuntimeSettings.snapshot());if(!LiveRenderLoopPolicy.requiresProjection(s.renderMode)){val raw=animationRenderer.render(s.animationEffect,s.animationColour,s.brightness,frames,s.effectParameters);if(!sendFrame(smoother.apply(raw),null,began,++frames,started,s))break;sleep(started,s.fps);continue};val p=projection?:break;if(!reconcileSources(p,s))break;if(s.requiresAudio()&&AudioRecordDrainPolicy.drainLatestFullBlock(samples,latestSamples){buffer->recorder?.read(buffer,0,buffer.size,AudioRecord.READ_NON_BLOCKING)?:0}){analyzer.configureBeatTracking(s.effectParameters.beatThreshold,started);features=analyzer.analyzeStereo(latestSamples,latestSamples.size,s.sensitivity,s.effectParameters.beatThreshold,started)} else if(!s.requiresAudio()) features=null;var sent=true;if(!s.requiresVideo()){val raw=audioRenderer.render(s.effect,features?:silence,s.brightness,frames,s.effectParameters);sent=sendFrame(smoother.apply(raw,FrameSmoothingPolicy.immediateBlack(s.brightness,features?.signalPresent==true)),features,began,++frames,started,s)}else{val captureReader=reader?:break;when(FreshFrameDispatcher.dispatch(acquireLatest={captureReader.acquireLatestImage()},release={it.close()},render={fresh->if(processor.copyImage(fresh))processor.compose(features,s,started)else null},send={raw->val frame=smoother.apply(raw,FrameSmoothingPolicy.immediateBlack(s,features?.signalPresent));sent=sendFrame(frame,features,began,++frames,started,s)})){FreshFrameDispatcher.Result.NO_FRESH_FRAME->Unit;FreshFrameDispatcher.Result.SENT->Unit;FreshFrameDispatcher.Result.RENDER_REJECTED->{status=CaptureStatus.VIDEO_UNAVAILABLE_OR_PROTECTED;VideoCaptureFailurePolicy.blackoutAndTerminate{router?.stop();router=null};broadcast();break}}};if(!sent)break;sleep(started,s.fps)};stop()}

 private fun sendFrame(frame:ByteArray, features:AudioFeatures?, began:Long, frames:Long, frameStartedNanos:Long, settings:AudioSettings):Boolean {
  fun sendToActiveRoute() { requireNotNull(router).send(frame) }
  return try {
   sendToActiveRoute()
   recordFrameSent(features,began,frames,frameStartedNanos,settings.fps)
   true
  } catch(_:Exception) {
   if(!reconnectRoute(settings)) { terminateRouteLost(); return false }
   return try { sendToActiveRoute(); recordFrameSent(features,began,frames,frameStartedNanos,settings.fps); true } catch(_:Exception) { terminateRouteLost(); false }
  }
 }
 private fun recordFrameSent(features:AudioFeatures?,began:Long,frames:Long,frameStartedNanos:Long,fps:Int){val now=System.nanoTime(); LocalStatusStore.recordFrameTiming(now-frameStartedNanos,CaptureCadence.periodNanos(fps)); val old=LocalStatusStore.snapshot(); LocalStatusStore.update(old.copy(frames=frames,fps=frames*1_000_000_000f/(now-began).coerceAtLeast(1),lastSendSucceeded=true,rms=features?.rms?:old.rms,peak=features?.peak?:old.peak)); MqttControlService.notifyDiagnosticChanged()}
 private fun reconnectRoute(settings:AudioSettings):Boolean {
  val failed=router?:return false; router=null; runCatching { failed.stop() }
  val result=OutputRouteReconnect.connect(cancelled={ !running.get() }) {
   val candidate=when(settings.outputMode){
    OutputMode.WLED -> WledCapturePreflight.bind(settings)?.let { binding -> OutputRouter.create(settings,wledBindingId=binding) }
    OutputMode.HYPERION -> HyperionCapturePreflight.bind(settings)?.let { binding -> OutputRouter.create(settings,hyperionBindingId=binding) }
   } ?: return@connect null
   try { candidate.start(); candidate } catch(_:Exception) { candidate.stop(); null }
  }
  val restored=result.binding?:return false
  if(!running.get()){restored.stop();return false}; router=restored; return true
 }
 private fun terminateRouteLost(){ lifecycle.stop { status=CaptureStatus.ROUTE_LOST; LocalStatusStore.update(LocalStatusStore.snapshot().copy(captureStatus=status,lastSendSucceeded=false)) } }
 private fun terminateVoiceInputLost(){ lifecycle.stop { status=CaptureStatus.MICROPHONE_ROUTE_LOST; LocalStatusStore.update(LocalStatusStore.snapshot().copy(captureStatus=status,lastSendSucceeded=false)) } }
 private fun sleep(start:Long,fps:Int){val n=CaptureCadence.remainingSleepNanos(start,System.nanoTime(),fps);if(n>0)Thread.sleep(n/1_000_000L,(n%1_000_000L).toInt())}
 private fun stop(){ lifecycle.stop() }
 private fun performTeardown(){
  val failures=mutableListOf<String>()
  fun attempt(name:String,action:()->Unit){try{action()}catch(_:Exception){failures+=name}}
  attempt("running"){running.set(false)}
  attempt("voice device callback"){(getSystemService(AUDIO_SERVICE)as AudioManager).unregisterAudioDeviceCallback(voiceInputDeviceCallback)}
  attempt("voice route callback"){recorder?.removeOnRoutingChangedListener(voiceInputRouteListener)}
  attempt("voice device"){selectedVoiceInputDeviceId=null}
  // A consumed router owns its route until stopped; only then can admission be reopened.
  attempt("router"){router?.stop();router=null}
  attempt("admission"){admission.finish();OutputDiagnosticAdmission.releaseCapture()}
  attempt("recorder"){releaseAudio()};attempt("video"){releaseVideo()};attempt("projection"){projection?.stop();projection=null};attempt("renderer"){LiveRendererSettings.end()};attempt("alive"){alive=false}
  attempt("status"){if(status.isActive)status=CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT};attempt("local status"){if(status==CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT)LocalStatusStore.reset()}
  if(failures.isNotEmpty()){Log.w(TAG,"$CLEANUP_FAILURE_DIAGNOSTIC: ${failures.joinToString()}")}
  attempt("broadcast"){invalidAdmissionGeneration?.let{generation->invalidAdmissionGeneration=null;broadcastAdmissionFailed(generation)}?:broadcast()};attempt("foreground"){stopForeground(STOP_FOREGROUND_REMOVE)};attempt("self"){stopSelf()}
 }
 override fun onDestroy(){stop();worker.shutdownNow();super.onDestroy()}
 private fun channel()=(getSystemService(NOTIFICATION_SERVICE)as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.notification_capture_channel),NotificationManager.IMPORTANCE_LOW))
 private fun notification(animation:Boolean=false)=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(if(animation) getString(R.string.notification_animation_title) else getString(R.string.notification_capture_channel)).setContentText(if(animation) getString(R.string.notification_animation_text) else getString(R.string.notification_capture_text)).setOngoing(true).build()
 private fun broadcastAdmissionFailed(generation:Long){MqttControlService.notifyDiagnosticChanged();sendBroadcast(Intent(ACTION_CAPTURE_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_ADMISSION_GENERATION,generation).putExtra(EXTRA_ADMISSION_FAILED,true))}
 private fun broadcast(admissionGeneration:Long?=null){MqttControlService.notifyDiagnosticChanged();sendBroadcast(Intent(ACTION_CAPTURE_STATE_CHANGED).setPackage(packageName).also{if(admissionGeneration!=null&&admissionGeneration!=Long.MIN_VALUE)it.putExtra(EXTRA_ADMISSION_GENERATION,admissionGeneration)})}
}
