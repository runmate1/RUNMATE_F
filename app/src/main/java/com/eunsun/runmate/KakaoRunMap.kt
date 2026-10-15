package com.eunsun.runmate

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapType
import com.kakao.vectormap.MapView
import com.kakao.vectormap.MapViewInfo
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.route.RouteLine
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet

@Composable
internal fun KakaoRunMap(points: List<RoutePoint>, modifier: Modifier = Modifier, initialLocation: LatLng? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val kakaoMap = remember { mutableStateOf<KakaoMap?>(null) }
    val routeLine = remember { mutableStateOf<RouteLine?>(null) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val appKeyMissing = BuildConfig.KAKAO_MAP_APP_KEY.isBlank()

    Box(
        modifier = modifier.fillMaxWidth().height(250.dp).background(androidx.compose.ui.graphics.Color(0xFFF2F3F5)),
        contentAlignment = Alignment.Center
    ) {
        if (appKeyMissing) {
            Text("local.properties에 카카오 지도 키를 설정해 주세요.", fontSize = 13.sp)
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(250.dp),
                factory = {
                    MapView(context).also { mapView ->
                        mapViewRef.value = mapView
                        mapView.start(object : MapLifeCycleCallback() {
                            override fun onMapDestroy() { kakaoMap.value = null; routeLine.value = null }
                            override fun onMapError(error: Exception) { kakaoMap.value = null }
                        }, object : KakaoMapReadyCallback() {
                            override fun onMapReady(map: KakaoMap) { kakaoMap.value = map }
                            override fun getPosition(): LatLng = points.lastOrNull()?.let { LatLng.from(it.latitude, it.longitude) }
                                ?: initialLocation ?: LatLng.from(37.5665, 126.9780)
                            override fun getZoomLevel(): Int = 16
                            override fun getMapViewInfo(): MapViewInfo = MapViewInfo.from("openmap", MapType.NORMAL)
                            override fun getViewName(): String = "RunMateRunningMap"
                        })
                    }
                }
            )
        }
    }

    DisposableEffect(lifecycleOwner, mapViewRef.value) {
        val mapView = mapViewRef.value
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.resume()
                Lifecycle.Event.ON_PAUSE -> mapView?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.finish()
            mapViewRef.value = null
            kakaoMap.value = null
            routeLine.value = null
        }
    }

    LaunchedEffect(points, kakaoMap.value) {
        val map = kakaoMap.value ?: return@LaunchedEffect
        if (points.isEmpty()) return@LaunchedEffect
        val coords = points.map { LatLng.from(it.latitude, it.longitude) }
        if (points.size == 1) {
            map.moveCamera(CameraUpdateFactory.newCenterPosition(coords.last()))
            return@LaunchedEffect
        }
        val styles = RouteLineStyles.from(RouteLineStyle.from(10f, AndroidColor.rgb(255, 125, 0)))
        val stylesSet = RouteLineStylesSet.from("runmate-running-route", styles)
        val segment = RouteLineSegment.from(coords).setStyles(styles)
        val options = RouteLineOptions.from(segment).setStylesSet(stylesSet)
        val existing = routeLine.value
        if (existing == null) routeLine.value = map.getRouteLineManager()?.getLayer()?.addRouteLine(options)
        else existing.changeSegments(segment)
        map.moveCamera(CameraUpdateFactory.newCenterPosition(coords.last()))
    }
}
