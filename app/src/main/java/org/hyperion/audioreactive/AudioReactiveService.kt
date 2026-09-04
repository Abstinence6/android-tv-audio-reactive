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
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One user-approved projection owns only the sources required by the selected mode. */
class AudioReactiveService : Service() {
 companion object { const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_RESULT_DATA="resultData"; const val EXTRA_WLED_ROUTE_BINDING="wledRouteBinding"; const val EXTRA_HYPERION_ROUTE_BINDING="hyperionRouteBinding"; const val EXTRA_ADMISSION_GENERATION="admissionGeneration"; const val EXTRA_ADMISSION_FAILED="admissionFailed"; const val ACTION_CAPTURE_STATE_CHANGED="org.hyperion.audioreactive.CAPTURE_STATE_CHANGED"; private const val CHANNEL="capture"; private const val ID=7; @Volatile private var alive=false; @Volatile private var status=CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT; fun exists()=alive; fun captureStatus()=status; fun stopExisting(context:android.content.Context){if(alive)context.stopService(Intent(context,AudioReactiveService::class.java))} }
 private val running=AtomicBoolean(); private val worker=Executors.newSingleThreadExecutor(); private var projection:MediaProjection?=null; private var recorder:AudioRecord?=null; private var reader:ImageReader?=null; private var display:android.hardware.display.VirtualDisplay?=null; private var router:OutputRouter?=null
 private val admission = ServiceRouteAdmission(::discardRouteBindings)
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  val generation=intent?.getLongExtra(EXTRA_ADMISSION_GENERATION,Long.MIN_VALUE)?:Long.MIN_VALUE
  val ids=RouteBindingIds(intent?.getStringExtra(EXTRA_WLED_ROUTE_BINDING),intent?.getStringExtra(EXTRA_HYPERION_ROUTE_BINDING))
  // Reserve first: alive is not visible until the worker has consumed the exact handoff.
  // A rejected duplicate never signals failure for the still-pending first admission.
  if(!admission.reserve(ids))return START_NOT_STICKY
  val data=intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
  if(intent?.getIntExtra(EXTRA_RESULT_CODE,0)!=Activity.RESULT_OK||data==null){admission.discardPending();broadcastAdmissionFailed(generation);return START_NOT_STICKY}
  val frozen=RuntimeSettings.snapshot()
  val valid=if(frozen.outputMode==OutputMode.HYPERION)HyperionRouteBindings.has(ids.hyperion,frozen) else WledRouteBindings.has(ids.wled,frozen)
  if(!valid){admission.discardPending();broadcastAdmissionFailed(generation);return START_NOT_STICKY}
  channel(); startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
  worker.execute { start(data,frozen,generation) }
  return START_NOT_STICKY
 }
 private fun discardRouteBindings(ids:RouteBindingIds){WledRouteBindings.discard(ids.wled);HyperionRouteBindings.discard(ids.hyperion)}
 private fun start(data:Intent,s:AudioSettings,generation:Long){ try { status=CaptureStatus.PREPARING_PROJECTION; LocalStatusStore.reset("preparing projection"); broadcast(); val p=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(Activity.RESULT_OK,data)?:error("projection"); projection=p; p.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stop() }},null); if(s.requiresAudio()) recorder=createAudio(p); if(s.requiresVideo()) createVideo(p,s.captureFrame()); router=admission.consume { ids -> OutputRouter.create(s,ids.wled,ids.hyperion) }; alive=true; LiveRendererSettings.begin(); broadcast(generation); running.set(true); router!!.start(); status=when(s.renderMode){RenderMode.AUDIO->CaptureStatus.CAPTURE_ACTIVE_AUDIO;RenderMode.VIDEO->CaptureStatus.CAPTURE_ACTIVE_VIDEO;RenderMode.VIDEO_AUDIO->CaptureStatus.CAPTURE_ACTIVE_VIDEO_AUDIO}; LocalStatusStore.update(LocalCaptureStatus("active ${s.renderMode.label}", if(s.outputMode==OutputMode.WLED)s.selectedWledDevices().map{it.name}else listOf("Hyperion"),s.selectedWledDevices().filter{s.calibrationFor(it)?.validFor(it)==true}.map{it.name},s.selectedWledDevices().filterNot{WledCalibrationPolicy.routeable(s,it)}.map{it.name})); broadcast(); if(s.requiresVideo()) videoLoop(s) else audioLoop(s) } catch(_:Exception){status=CaptureStatus.ROUTER_INIT_FAILED;LocalStatusStore.reset("route initialization failed");broadcastAdmissionFailed(generation);broadcast();stop()} }
 private fun createAudio(p:MediaProjection):AudioRecord { if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)error("audio permission"); val min=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096); return AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(min).setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(p).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()).build().also{it.startRecording()} }
 private fun createVideo(p:MediaProjection,s:SourceFrameSpec){ reader=ImageReader.newInstance(320,180,android.graphics.PixelFormat.RGBA_8888,2); display=p.createVirtualDisplay("audio-reactive-video",320,180,1,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,null) }
 private fun audioLoop(initial:AudioSettings){val samples=ShortArray(CaptureCadence.ANALYSIS_SAMPLES);val a=PcmAnalyzer();val r=EffectFrameRenderer(initial.captureFrame().width);val smoother=RgbFrameSmoother(initial.captureFrame().bytes);var tick=0L;val began=System.nanoTime();while(running.get()){val now=System.nanoTime();val n=recorder!!.read(samples,0,samples.size,AudioRecord.READ_BLOCKING);if(n<=0)break;val s=LiveRendererSettings.apply(RuntimeSettings.snapshot());val f=a.analyze(samples,n,s.sensitivity);val raw=r.render(s.effect,f,s.brightness,tick++,s.effectParameters);if(!sendFrame(smoother.apply(raw,FrameSmoothingPolicy.immediateBlack(s.brightness,f.signalPresent)),f,began,tick))break;sleep(now,s.fps)};stop()}
 private fun videoLoop(initial:AudioSettings){val processor=VideoFrameProcessor(initial.captureFrame().width,initial.captureFrame().height);val smoother=RgbFrameSmoother(initial.captureFrame().bytes);val samples=ShortArray(CaptureCadence.ANALYSIS_SAMPLES);val analyzer=if(initial.requiresAudio())PcmAnalyzer()else null;var features:AudioFeatures?=null;var frames=0L;val began=System.nanoTime();while(running.get()){val started=System.nanoTime();if(analyzer!=null){val n=recorder!!.read(samples,0,samples.size,AudioRecord.READ_NON_BLOCKING);if(n>0)features=analyzer.analyze(samples,n,LiveRendererSettings.apply(RuntimeSettings.snapshot()).sensitivity)};val image=reader!!.acquireLatestImage();if(image!=null)try{if(processor.copyImage(image)){frames++;val s=LiveRendererSettings.apply(RuntimeSettings.snapshot());val raw=processor.compose(features,s);if(!sendFrame(smoother.apply(raw,FrameSmoothingPolicy.immediateBlack(s.brightness,features?.signalPresent)),features,began,frames))break}else{status=CaptureStatus.VIDEO_UNAVAILABLE_OR_PROTECTED;VideoCaptureFailurePolicy.blackoutAndTerminate{router?.stop();router=null};broadcast();break}}finally{image.close()};sleep(started,RuntimeSettings.snapshot().fps)};stop()}
 private fun sendFrame(frame:ByteArray, features:AudioFeatures?, began:Long, frames:Long):Boolean = try { router?.send(frame); val old=LocalStatusStore.snapshot(); LocalStatusStore.update(old.copy(frames=frames,fps=frames*1_000_000_000f/(System.nanoTime()-began).coerceAtLeast(1),lastSend="ok",rms=features?.rms?:old.rms,peak=features?.peak?:old.peak)); true } catch(_:Exception) { status=CaptureStatus.ROUTE_LOST; LocalStatusStore.update(LocalStatusStore.snapshot().copy(stage="route lost; capture stopped",lastSend="failed")); broadcast(); false }
 private fun sleep(start:Long,fps:Int){val n=CaptureCadence.remainingSleepNanos(start,System.nanoTime(),fps);if(n>0)Thread.sleep(n/1_000_000L,(n%1_000_000L).toInt())}
 private fun stop(){
  admission.finish() // Discards only an unconsumed handoff; a consumed router owns its route.
  if(!running.getAndSet(false)&&!alive)return
  runCatching{router?.stop()};router=null;runCatching{recorder?.stop()};runCatching{recorder?.release()};recorder=null;runCatching{display?.release()};display=null;runCatching{reader?.close()};reader=null;runCatching{projection?.stop()};projection=null;alive=false;LiveRendererSettings.end();if(status.isActive)status=CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT; if(status==CaptureStatus.NEEDS_MEDIA_PROJECTION_CONSENT)LocalStatusStore.reset();broadcast();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
 }
 override fun onDestroy(){stop();worker.shutdownNow();super.onDestroy()}
 private fun channel()=(getSystemService(NOTIFICATION_SERVICE)as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL,"Playback capture",NotificationManager.IMPORTANCE_LOW))
 private fun notification()=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Playback capture",).setContentText("User-approved capture active.").setOngoing(true).build()
 private fun broadcastAdmissionFailed(generation:Long){
  MqttControlService.notifyDiagnosticChanged()
  sendBroadcast(Intent(ACTION_CAPTURE_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_ADMISSION_GENERATION,generation).putExtra(EXTRA_ADMISSION_FAILED,true))
 }
 private fun broadcast(admissionGeneration:Long?=null){MqttControlService.notifyDiagnosticChanged();sendBroadcast(Intent(ACTION_CAPTURE_STATE_CHANGED).setPackage(packageName).also{if(admissionGeneration!=null&&admissionGeneration!=Long.MIN_VALUE)it.putExtra(EXTRA_ADMISSION_GENERATION,admissionGeneration)})}
}
