package com.itxiaoer.dis.core.aspect;

import com.itxiaoer.commons.core.page.Responsive;
import com.itxiaoer.dis.commons.annotation.Dis;
import com.itxiaoer.dis.commons.exception.DisException;
import com.itxiaoer.dis.commons.logger.DisLogger;
import com.itxiaoer.dis.core.config.DisProperties;
import com.itxiaoer.dis.core.util.ReflectUtils;
import com.itxiaoer.dis.store.DisTemplate;
import com.itxiaoer.dis.store.id.Md5ID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;

/**
 * @author : liuyk
 */
@Aspect
@Component
public class DisAspect {

    @Resource
    private DisProperties disProperties;

    @Resource
    private DisTemplate disTemplate;


    @Resource
    private DisLogger disLogger;

    @Pointcut("@annotation(com.itxiaoer.dis.commons.annotation.Dis)")
    private void dis() {
    }

    @Around("dis()")
    public Object around(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Dis dis = signature.getMethod().getAnnotation(Dis.class);
        String content = ReflectUtils.find(joinPoint);
        String id = new Md5ID(disProperties.getAppId(), content).id();
        disLogger.debug(DisAspect.class, " dis  begin  [id = {} , content = {} ] ", id, content);
        // 区分是存在，还是出现异常
        Boolean success = this.disTemplate.setNx(id, content, dis.expireTime());
        disLogger.debug(DisAspect.class, " dis  store  [id = {} , content = {},success = {} ] ", id, content, success);
        // not delete id
        if (!success) {
            // false
            // id exists? not delete
            String value = this.disTemplate.get(id);
            if (!StringUtils.isEmpty(value)) {
                throw new DisException("the same request is already being processed");
            }
            // false store error
            this.disTemplate.delete(id);
            throw new DisException("store is not working ");
        }
        try {

            Object proceed;
            try {
                proceed = joinPoint.proceed();
            } catch (Throwable throwable) {
                disLogger.error(DisAspect.class, throwable.getMessage(), throwable);
                throw new DisException(throwable);
            }

            if (!(proceed instanceof Responsive)) {
                throw new DisException(signature.getClass().getName() + "#" + signature.getMethod().getName() + " return response must be implements Responsive. ");
            }
            // 此处服务器断电如何处理？单位时间请求无法处理? TODO
            // 因为处理完业务逻辑后，机器宕机，此时分为两种情况：
            // 1，业务处理成功，但是由于机器宕机，此时无法返回结果给用户，此时幂等性相关的Key已经存在。
            // 2，业务处理失败，但是由于机器宕机，此时也无法返回结果给用户，此时幂等性相关的Key已经存在，导致用户无法完成业务逻辑，
            // 有不好判断用户的重复请求是正常的还是非正常的请求，都会做幂等处理，此时用户就完成不聊操作了
            Responsive response = (Responsive) proceed;
            // success
            if (response.isSuccess()) {
                // 判断结果，
                return proceed;
            }
            this.disTemplate.delete(id);
            // fail
            return proceed;
        } catch (Exception e) {
            this.disTemplate.delete(id);
            throw e;
        }

    }
}
