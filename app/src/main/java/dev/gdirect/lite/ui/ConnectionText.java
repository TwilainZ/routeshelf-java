package dev.gdirect.lite.ui;

import dev.gdirect.lite.auth.RememberedAuthorization;
import dev.gdirect.lite.network.ConnectionHealth;

/** Shared status wording for the browser indicator, settings and connection screen. */
public final class ConnectionText {
    public static String headline(ConnectionHealth.Snapshot status,boolean authorized,boolean restoring,boolean checking){
        if(!status.online)return "设备网络未连接";
        if(checking)return "正在检测 Google 连接";
        if(restoring)return "正在恢复 Google 账户连接";
        if(status.api.state==ConnectionHealth.State.FAILED)return "云盘连接失败，点击处理";
        if(status.api.state==ConnectionHealth.State.RESPONSE_ERROR)return "Google 已响应，访问需要处理";
        if(status.api.state==ConnectionHealth.State.CHECKING)return "正在连接 Google 云盘";
        if(status.api.state==ConnectionHealth.State.READY){
            if(!authorized)return "Google 线路已验证 · 账户未连接";
            if(status.download.state==ConnectionHealth.State.FAILED)return "云盘已连接 · 下载连接失败";
            if(status.download.state==ConnectionHealth.State.RESPONSE_ERROR)return "云盘已连接 · 下载访问需要处理";
            return status.api.evidence==ConnectionHealth.Evidence.PROBE?"账户已授权 · 线路已验证":"云盘已连接";
        }
        if(status.api.state==ConnectionHealth.State.STALE)return "连接状态需要重新确认";
        return authorized?"账户已授权 · 数据连接待检测":"Google 连接尚未检测";
    }
    public static String account(boolean authorized,boolean writable,RememberedAuthorization.State restore,boolean revoking){
        if(revoking)return "正在撤销授权";
        if(authorized)return writable?"已授权 · 可以管理文件":"已授权 · 只读访问";
        if(restore==RememberedAuthorization.State.RESTORING)return "正在恢复上次连接";
        if(restore==RememberedAuthorization.State.NEEDS_ACTION)return "需要点击连接并确认授权";
        if(restore==RememberedAuthorization.State.FAILED)return "账户恢复暂未完成，可重新连接";
        return "尚未连接 Google 账户";
    }
    public static String service(ConnectionHealth.Result result,boolean download){
        switch(result.state){
            case CHECKING:return "正在寻找并验证可用线路…";
            case STALE:return "上次验证已过期，请重新检测";
            case READY:
                if(download)return result.evidence==ConnectionHealth.Evidence.DOWNLOAD?"最近一次下载已完成":"文件服务可响应 · 尚未完成实际下载";
                return result.evidence==ConnectionHealth.Evidence.PROBE?"线路验证通过 · 尚未验证账户访问":"云盘请求已成功响应";
            case RESPONSE_ERROR:
                switch(result.problem){
                    case AUTHORIZATION:return "Google 已响应，需要重新连接账户";
                    case PERMISSION:return "Google 已响应，请检查文件权限或配额";
                    case NOT_FOUND:return "Google 已响应，文件不存在或无权访问";
                    default:return result.code>=500||result.code==429?"Google 已响应，服务暂时不可用":"Google 已响应，请检查本次操作";
                }
            case FAILED:
                switch(result.problem){
                    case CERTIFICATE:return "证书验证失败，已停止连接";
                    case TIMEOUT:return "连接超时，可重新检测线路";
                    default:return "数据连接失败，可重新检测或查看日志";
                }
            default:return download?"尚未验证下载服务":"尚未验证云盘服务";
        }
    }
    private ConnectionText(){}
}
