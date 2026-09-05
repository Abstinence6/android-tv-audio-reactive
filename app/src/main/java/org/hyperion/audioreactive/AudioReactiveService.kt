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
  const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_RESULT_DATA="resultData"; const val EXTRA_WLED_ROUTE_BINDING="wledRouteBinding"; const val EXTRA_HYPERION_ROUTE_BINDING="hyperionRouteBinding"; const val EXTRA_ADMISSION_GENERATION="admissionGeneration"; const val EXTRA_ADMISSION_FAILED="admissionFailed"; const val ACTION_CAPTURE_STATE_CHANGED="org.hyperion.audioreactive.CAPTURE_STATE_CHANGED"; private const val CHANNEL="capture"; private const val ID=7
  internal const val STARTUP_FAILURE_DIAGNOSTIC="startup failed; local cleanup completed"
  internal const val CLEANUP_FAILURE_DIAGNOSTIC="capture stopped; local cleanup incomplete"
  private const val TAG="AudioReactiveService"
  @Volatile private var alive=false; @Volatile private var status=CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT
  fun exists()=alive; fun captureStatus()=status; fun stopExisting(context:android.content.Context){if(alive)context.stopService(Intent(context,AudioReactiveService::class.java))}
 }
 private val running=AtomicBoolean(); private val worker=Executors.newSingleThreadExecutor(); private var projection:MediaProjection?=null; private var recorder:AudioRecord?=null; private var reader:ImageReader?=null; private var display:android.hardware.display.VirtualDisplay?=null; private var router:OutputRouter?=null; private var invalidAdmissionGeneration:Long?=null
 private val admission = ServiceRouteAdmission(::discardRouteBindings)
 private val lifecycle = CaptureServiceLifecycle(::performTeardown)
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  val generation=intent?.getLongExtra(EXTRA_ADMISSION_GENERATION,Long.MIN_VALUE)?:Long.MIN_VALUE
  val ids=RouteBindingIds(intent?.getStringExtra(EXTRA_WLED_ROUTE_BINDING),intent?.getStringExtra(EXTRA_HYPERION_ROUTE_BINDING))
  // The lifecycle gate linearizes route admission against onStop/onDestroy cancellation.
  if(!lifecycle.beginStart { admission.reserve(ids) }) { admission.discardLifecycleRejectedStart(ids); return START_NOT_STICKY }
  val data=intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
  if(intent?.getIntExtra(EXTRA_RESULT_CODE,0)!=Activity.RESULT_OK||data==null){rejectInvalidStart(generation);return START_NOT_STICKY}
  val frozen=RuntimeSettings.snapshot()
  val valid=if(frozen.outputMode==OutputMode.HYPERION)HyperionRouteBindings.has(ids.hyperion,frozen) else WledRouteBindings.has(ids.wled,frozen)
  if(!valid){rejectInvalidStart(generation);return START_NOT_STICKY}
  // A stop between validation and foreground startup cancels the pending binding instead.
  if(!lifecycle.whileStarting { channel(); startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) }) { admission.discardPending(); return START_NOT_STICKY }
  worker.execute { start(data,frozen,generation) }
  return START_NOT_STICKY
 }
 private fun discardRouteBindings(ids:RouteBindingIds){WledRouteBindings.discard(ids.wled);HyperionRouteBindings.discard(ids.hyperion)}
 private fun rejectInvalidStart(generation:Long){invalidAdmissionGeneration=generation;lifecycle.stop()}
 private fun start(data:Intent,s:AudioSettings,generation:Long){
  try {
   if(!lifecycle.whileStarting { status=CaptureStatus.PREPARING_PROJECTION; LocalStatusStore.reset("preparing projection"); broadcast() }) return
   if(!lifecycle.acquire(
     acquire = { (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(Activity.RESULT_OK,data)?:error("projection") },
     release = { it.stop() },
     assign = { projection=it },
   )) return
   val p=projection?:return
   if(!lifecycle.whileStarting { p.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stop()}},null) }) return
   if(s.requiresAudio()&&!lifecycle.acquire(acquire={createAudio(p)},release={it.stop();it.release()},assign={recorder=it})) return
   if(s.requiresVideo()&&!createVideo(p)) return
   if(!lifecycle.acquire(acquire={ admission.consume { ids -> OutputRouter.create(s,ids.wled,ids.hyperion) } },release={it.stop()},assign={router=it})) return
   if(!lifecycle.activate {
    alive=true; LiveRendererSettings.begin(); broadcast(generation); running.set(true); router!!.start()
    status=when(s.renderMode){RenderMode.AUDIO->CaptureStatus.CAPTURE_ACTIVE_AUDIO;RenderMode.VIDEO->CaptureStatus.CAPTURE_ACTIVE_VIDEO;RenderMode.VIDEO_AUDIO->CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO}
    LocalStatusStore.update(LocalCaptureStatus("active ${s.renderMode.label}", if(s.outputMode==OutputMode.WLED)s.selectedWledDevices().map{it.name}else listOf("Hyperion"),s.selectedWledDevices().filter{s.calibrationFor(it)?.validFor(it)==true}.map{it.name},s.selectedWledDevices().filterNot{WledCalibrationPolicy.routeable(s,it)}.map{it.name}))
    broadcast()
   }) return
   if(s.requiresVideo()) videoLoop(s) else audioLoop(s)
  } catch(failure:Exception) {
   Log.w(TAG, "$STARTUP_FAILURE_DIAGNOSTIC (${failure.javaClass.simpleName})")
   status=CaptureStatus.ROUTER_INIT_FAILED;LocalStatusStore.reset(STARTUP_FAILURE_DIAGNOSTIC);broadcastAdmissionFailed(generation);broadcast();stop()
  }
 }
 private fun createAudio(p:MediaProjection):AudioRecord { if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)error("audio permission"); val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096); return AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(min).setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(p).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()).build().also{it.startRecording()} }
 private fun createVideo(p:MediaProjection):Boolean {
  if(!lifecycle.acquire(acquire={ImageReader.newInstance(320,180,android.graphics.PixelFormat.RGBA_8888,2)},release={it.close()},assign={reader=it})) return false
  val captureReader=reader?:return false
  return lifecycle.acquire(acquire={p.createVirtualDisplay("audio-reactive-video",320,180,1,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,captureReader.surface,null,null)},release={it?.release()},assign={display=it})
 }
 private fun audioLoop(initial:AudioSettings){val samples=ShortArray(CaptureCadence.ANALYSIS_SAMPLES);val a=PcmAnalyzer();val r=EffectFrameRenderer(initial.captureFrame().width);val smoother=RgbFrameSmoother(initial.captureFrame().bytes);var tick=0L;val began=System.nanoTime();while(running.get()){val now=System.nanoTime();val n=recorder!!.read(samples,0,samples.size,AudioRecord.READ_BLOCKING);if(n<=0)break;val s=LiveRendererSettings.apply(RuntimeSettings.snapshot());val f=a.analyze(samples,n,s.sensitivity);val raw=r.render(s.effect,f,s.brightness,tick++,s.effectParameters);if(!sendFrame(smoother.apply(raw,FrameSmoothingPolicy.immediateBlack(s.brightness,f.signalPresent)),f,began,tick))break;sleep(now,s.fps)};stop()}
 private fun videoLoop(initial:AudioSettings){val processor=VideoFrameProcessor(initial.captureFrame().width,initial.captureFrame().height);val smoother=RgbFrameSmoother(initial.captureFrame().bytes);val retainedFrame=VideoRealtimeFrameCache();val samples=ShortArray(CaptureCadence.ANALYSIS_SAMPLES);val analyzer=if(initial.requiresAudio())PcmAnalyzer()else null;var features:AudioFeatures?=null;var frames=0L;val began=System.nanoTime();while(running.get()){val started=System.nanoTime();if(analyzer!=null){val n=recorder!!.read(samples,0,samples.size,AudioRecord.READ_NON_BLOCKING);if(n>0)features=analyzer.analyze(samples,n,LiveRendererSettings.apply(RuntimeSettings.snapshot()).sensitivity)};val image=reader!!.acquireLatestImage();if(image!=null)try{if(processor.copyImage(image)){val s=LiveRendererSettings.apply(RuntimeSettings.snapshot());val raw=processor.compose(features,s);retainedFrame.update(smoother.apply(raw,FrameSmoothingPolicy.immediateBlack(s.brightness,features?.signalPresent)))}else{status=CaptureStatus.VIDEO_UNAVAILABLE_OR_PROTECTED;VideoCaptureFailurePolicy.blackoutAndTerminate{router?.stop();router=null};broadcast();break}}finally{image.close()};val frame=retainedFrame.current();if(frame!=null&&!sendFrame(frame,features,began,++frames))break;sleep(started,RuntimeSettings.snapshot().fps)};stop()}
 private fun sendFrame(frame:ByteArray, features:AudioFeatures?, began:Long, frames:Long):Boolean = try { router?.send(frame); val old=LocalStatusStore.snapshot(); LocalStatusStore.update(old.copy(frames=frames,fps=frames*1_000_000_000f/(System.nanoTime()-began).coerceAtLeast(1),lastSend="ok",rms=features?.rms?:old.rms,peak=features?.peak?:old.peak)); true } catch(_:Exception) { status=CaptureStatus.ROUTE_LOST; LocalStatusStore.update(LocalStatusStore.snapshot().copy(stage="route lost; capture stopped",lastSend="failed")); broadcast(); false }
 private fun sleep(start:Long,fps:Int){val n=CaptureCadence.remainingSleepNanos(start,System.nanoTime(),fps);if(n>0)Thread.sleep(n/1_000_000L,(n%1_000_000L).toInt())}
 private fun stop(){ lifecycle.stop() }
 private fun performTeardown(){
  val failures=mutableListOf<String>()
  fun attempt(name:String,action:()->Unit){try{action()}catch(_:Exception){failures+=name}}
  attempt("running"){running.set(false)}
  // A consumed router owns its route until stopped; only then can admission be reopened.
  attempt("router"){router?.stop();router=null}
  attempt("admission"){admission.finish()}
  attempt("recorder.stop"){recorder?.stop()};attempt("recorder.release"){recorder?.release();recorder=null};attempt("display"){display?.release();display=null};attempt("reader"){reader?.close();reader=null};attempt("projection"){projection?.stop();projection=null};attempt("renderer"){LiveRendererSettings.end()};attempt("alive"){alive=false}
  attempt("status"){if(status.isActive)status=CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT};attempt("local status"){if(status==CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT)LocalStatusStore.reset()}
  if(failures.isNotEmpty()){Log.w(TAG,"$CLEANUP_FAILURE_DIAGNOSTIC: ${failures.joinToString()}")}
  attempt("broadcast"){invalidAdmissionGeneration?.let{generation->invalidAdmissionGeneration=null;broadcastAdmissionFailed(generation)}?:broadcast()};attempt("foreground"){stopForeground(STOP_FOREGROUND_REMOVE)};attempt("self"){stopSelf()}
 }
 override fun onDestroy(){stop();worker.shutdownNow();super.onDestroy()}
 private fun channel()=(getSystemService(NOTIFICATION_SERVICE)as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"Playback capture",NotificationManager.IMPORTANCE_LOW))
 private fun notification()=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Playback capture",).setContentText("User-approved capture active.").setOngoing(true).build()
 private fun broadcastAdmissionFailed(generation:Long){MqttControlService.notifyDiagnosticChanged();sendBroadcast(Intent(ACTION_CAPTURE_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_ADMISSION_GENERATION,generation).putExtra(EXTRA_ADMISSION_FAILED,true))}
 private fun broadcast(admissionGeneration:Long?=null){MqttControlService.notifyDiagnosticChanged();sendBroadcast(Intent(ACTION_CAPTURE_STATE_CHANGED).setPackage(packageName).also{if(admissionGeneration!=null&&admissionGeneration!=Long.MIN_VALUE)it.putExtra(EXTRA_ADMISSION_GENERATION,admissionGeneration)})}
}
