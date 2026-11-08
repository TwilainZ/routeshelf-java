package dev.gdirect.lite.ui;

import static org.junit.Assert.*;
import dev.gdirect.lite.network.ConnectionHealth;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ConnectionTextTest {
    private ConnectionHealth health(){ConnectionHealth health=new ConnectionHealth();health.online(true);return health;}
    @Test public void authorizationAloneDoesNotClaimDataConnectivity(){ConnectionHealth h=health();assertEquals("账户已授权 · 数据连接待检测",ConnectionText.headline(h.snapshot(),true,false,false));}
    @Test public void contentRootResponseDoesNotClaimASuccessfulDownload(){ConnectionHealth h=health();h.verified(h.begin(ConnectionHealth.Service.DOWNLOAD,h.generation()),ConnectionHealth.Evidence.PROBE);String label=ConnectionText.service(h.snapshot().download,true);assertTrue(label.contains("尚未完成实际下载"));assertFalse(label.contains("已完成"));}
    @Test public void permissionErrorsAskForAccessHandlingRatherThanARescan(){ConnectionHealth h=health();h.response(h.begin(ConnectionHealth.Service.API,h.generation()),403);assertTrue(ConnectionText.service(h.snapshot().api,false).contains("权限"));assertFalse(ConnectionText.headline(h.snapshot(),true,false,false).contains("连接失败"));h.response(h.begin(ConnectionHealth.Service.API,h.generation()),400);assertTrue(ConnectionText.service(h.snapshot().api,false).contains("本次操作"));}
    @Test public void downloadFailureRemainsVisibleWhenBrowsingWorks(){ConnectionHealth h=health();h.response(h.begin(ConnectionHealth.Service.API,h.generation()),200);h.failed(h.begin(ConnectionHealth.Service.DOWNLOAD,h.generation()),ConnectionHealth.Problem.TIMEOUT);assertTrue(ConnectionText.headline(h.snapshot(),true,false,false).contains("下载连接失败"));}
    @Test public void staleVerificationDoesNotRemainAConnectedHeadline(){AtomicLong clock=new AtomicLong();ConnectionHealth h=new ConnectionHealth(clock::get);h.online(true);h.response(h.begin(ConnectionHealth.Service.API,h.generation()),200);clock.set(ConnectionHealth.FRESH_MILLIS);assertTrue(ConnectionText.headline(h.snapshot(),true,false,false).contains("重新确认"));}
    @Test public void lossOfDeviceNetworkOverridesAuthorizationAndCachedSuccess(){ConnectionHealth h=health();h.response(h.begin(ConnectionHealth.Service.API,h.generation()),200);h.online(false);assertEquals("设备网络未连接",ConnectionText.headline(h.snapshot(),true,false,false));}
}
