package cn.eyeblue.blog.rest.user;

import cn.eyeblue.blog.config.exception.BadRequestException;
import cn.eyeblue.blog.config.exception.UtilException;
import cn.eyeblue.blog.rest.base.BaseEntityController;
import cn.eyeblue.blog.rest.base.Pager;
import cn.eyeblue.blog.rest.base.WebResult;
import cn.eyeblue.blog.rest.common.MailService;
import cn.eyeblue.blog.rest.common.NotificationResult;
import cn.eyeblue.blog.rest.core.Feature;
import cn.eyeblue.blog.rest.core.FeatureType;
import cn.eyeblue.blog.rest.preference.Preference;
import cn.eyeblue.blog.rest.preference.PreferenceService;
import cn.eyeblue.blog.rest.support.captcha.SupportCaptchaService;
import cn.eyeblue.blog.rest.support.session.SupportSession;
import cn.eyeblue.blog.rest.support.session.SupportSessionDao;
import cn.eyeblue.blog.rest.support.validation.SupportValidation;
import cn.eyeblue.blog.rest.support.validation.SupportValidationDao;
import cn.eyeblue.blog.rest.tank.TankService;
import cn.eyeblue.blog.rest.user.knock.UserKnock;
import cn.eyeblue.blog.rest.user.knock.UserKnockService;
import cn.eyeblue.blog.util.NetworkUtil;
import cn.eyeblue.blog.util.ValidationUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.io.IOException;
import java.util.Date;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/user")
public class UserController extends BaseEntityController<User, UserForm> {

    @Autowired
    UserService userService;

    @Autowired
    UserDao userDao;

    @Autowired
    TankService tankService;

    @Autowired
    SupportSessionDao supportSessionDao;

    @Autowired
    UserKnockService userKnockService;

    @Autowired
    BCryptPasswordEncoder bCryptPasswordEncoder;


    @Autowired
    SupportCaptchaService supportCaptchaService;

    @Autowired
    PreferenceService preferenceService;

    @Autowired
    MailService mailService;

    @Autowired
    SupportValidationDao supportValidationDao;

    public UserController() {
        super(User.class);
    }

    @Override
    @Feature(FeatureType.USER_MANAGE)
    public WebResult create(@Valid UserForm form) {

        //检查email的重复性。
        User operator = checkUser();
        User user = form.create(operator);

        //邮箱不能重复
        int count = userDao.countByEmail(user.getEmail());
        if (count > 0) {
            throw new UtilException("邮箱" + user.getEmail() + "已经存在，请使用其他邮箱。");
        }

        //username不能重复
        User dbUser = userDao.findTopByUsername(form.getUsername());
        if (dbUser != null) {
            throw new BadRequestException("用户名" + form.getUsername() + "已经存在，请使用其他名称。");
        }

        user = userDao.save(user);

        return success(user);
    }

    @Override
    @Feature(FeatureType.USER_MANAGE)
    public WebResult del(@PathVariable String uuid) {
        return super.del(uuid);
    }

    @Override
    @Feature(FeatureType.USER_MINE)
    public WebResult edit(@Valid UserForm form) {

        //检查email的重复性。
        User operator = checkUser();

        User user = this.check(form.getUuid());

        //验证权限
        checkMineEntityPermission(FeatureType.USER_MANAGE, FeatureType.USER_MINE, user.getUuid());


        String oldEmail = user.getEmail();
        String oldUsername = user.getUsername();
        form.update(user, operator);
        String newEmail = user.getEmail();
        String newUsername = user.getUsername();

        if (!oldEmail.equals(newEmail)) {
            //邮箱变更了时就要检查唯一性。
            int count = userDao.countByEmail(newEmail);
            if (count > 0) {
                throw new UtilException("邮箱" + newEmail + "已经存在，请使用其他邮箱。");
            }
            user.setEmailValidate(false);
        }


        if (!oldUsername.equals(newUsername)) {
            //用户名变更了时就要检查唯一性。
            User dbUser = userDao.findTopByUsername(newUsername);
            if (dbUser != null) {
                throw new UtilException("用户名" + newUsername + "已经存在，请使用其他用户名。");
            }
            user.setEmailValidate(false);
        }


        user = userDao.save(user);

        return success(user);


    }

    @Override
    @Feature(FeatureType.PUBLIC)
    public WebResult detail(@PathVariable String uuid) {
        User user = userService.detail(uuid);

        return success(user);
    }

    @Override
    @Feature(FeatureType.USER_MANAGE)
    public WebResult sort(@RequestParam String uuid1, @RequestParam Long sort1, @RequestParam String uuid2, @RequestParam Long sort2) {
        return super.sort(uuid1, sort1, uuid2, sort2);
    }


    @Feature(FeatureType.USER_MANAGE)
    @RequestMapping("/page")
    public WebResult page(

            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) Sort.Direction orderSort,
            @RequestParam(required = false) Sort.Direction orderUpdateTime,
            @RequestParam(required = false) Sort.Direction orderCreateTime,


            @RequestParam(required = false) Sort.Direction orderLastTime,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) User.Role role,
            @RequestParam(required = false, defaultValue = "false") Boolean autoComplete
    ) {


        Pager<User> pager = userService.page(
                page,
                pageSize,
                orderSort,
                orderUpdateTime,
                orderCreateTime,

                orderLastTime,
                username,
                email,
                phone,
                keyword,
                role,
                autoComplete
        );
        return success(pager);


    }


    //登录验证通过或者注册成功后内部自动登录。
    private User innerLogin(
            User user,
            HttpServletRequest request,
            HttpServletResponse response
    ) {

        String ip = NetworkUtil.getIpAddress(request);

        log.info(user.getNickname() + " login from ip:" + ip);

        //更新user上次登录ip和时间
        user.setLastIp(ip);
        user.setLastTime(new Date());
        userDao.save(user);

        //登录有效期一个月时间
        SupportSession supportSession = new SupportSession(user.getUuid(), ip, new Date(System.currentTimeMillis() + SupportSession.EXPIRY * 1000));
        supportSessionDao.save(supportSession);

        //设置浏览器cookie.
        Cookie cookie = new Cookie(WebResult.COOKIE_AUTHENTICATION, supportSession.getUuid());
        cookie.setMaxAge((int) SupportSession.EXPIRY);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        //把用户身份缓存起来。
        userService.cacheUser(supportSession.getUuid(), user);

        return user;
    }


    /**
     * 用户登录
     */
    @Feature(FeatureType.PUBLIC)
    @RequestMapping(value = "/login")
    public WebResult login(
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(required = false) String captcha,
            HttpServletRequest request,
            HttpServletResponse response) {


        String sessionId = request.getSession().getId();
        String ip = NetworkUtil.getIpAddress(request);
        //登录错误次数超过1次必须要输入验证码。
        if (userKnockService.needCaptcha(ip)) {
            if (!supportCaptchaService.validate(sessionId, captcha)) {
                userKnockService.log(sessionId, null, email, password, ip, UserKnock.Type.CAPTCHA_ERROR);
                throw new UtilException("验证码错误！");
            }
        }

        User user = userDao.findByEmail(email);


        if (user != null) {

            if (bCryptPasswordEncoder.matches(password, user.getPassword())) {

                this.innerLogin(user, request, response);
                return success(user);

            } else {
                userKnockService.log(sessionId, null, email, password, ip, UserKnock.Type.PASSWORD_ERROR);
            }
        } else {
            userKnockService.log(sessionId, null, email, password, ip, UserKnock.Type.NO_SUCH_USERNAME);
        }

        throw new BadRequestException("用户名或密码错误");

    }


    /**
     * 退出登录
     */
    @Feature(FeatureType.USER_MINE)
    @RequestMapping(value = "/logout")
    public WebResult logout(HttpServletRequest request,
                            HttpServletResponse response) {

        User user = checkUser();
        HttpSession httpSession = request.getSession();

        //尝试去获取cookie中的值
        Cookie[] cookies = request.getCookies();
        String authentication = null;
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (WebResult.COOKIE_AUTHENTICATION.equals(cookie.getName())) {
                    authentication = cookie.getValue();
                }
            }
        }

        //清除cookie
        Cookie cookie = new Cookie(WebResult.COOKIE_AUTHENTICATION, null);
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        //清除session.
        httpSession.removeAttribute(User.TAG);
        if (authentication != null) {

            Optional<SupportSession> optionalSupportSession = supportSessionDao.findById(authentication);
            //SupportSession supportSession = supportSessionDao.findOne(authentication);
            if (optionalSupportSession.isPresent()) {
                SupportSession supportSession = optionalSupportSession.get();
                supportSessionDao.delete(supportSession);
            }
        } else {
            throw new BadRequestException("已经退出，或者登录凭证已失效。");
        }

        return success();
    }


    /**
     * 用户自己修改密码
     */
    @Feature(FeatureType.USER_MINE)
    @RequestMapping(value = "/change/password")
    public WebResult changePassword(String oldPassword, String newPassword) {

        User user = checkUser();

        if (!bCryptPasswordEncoder.matches(oldPassword, user.getPassword())) {
            throw new UtilException("旧密码错误！");
        }

        if (newPassword.length() < 6) {
            throw new UtilException("密码位数不能低于6位");
        }

        user.setPassword(bCryptPasswordEncoder.encode(newPassword));
        userDao.save(user);

        return success();
    }


    /**
     * 管理员重置某个用户的密码
     */
    @Feature(FeatureType.USER_MANAGE)
    @RequestMapping(value = "/reset/password")
    public WebResult resetPassword(String userUuid, String newPassword) {

        User user = this.check(userUuid);

        if (newPassword.length() < 6) {
            throw new UtilException("密码位数不能低于6位");
        }

        user.setPassword(bCryptPasswordEncoder.encode(newPassword));
        userDao.save(user);

        return success();

    }

    /**
     * 验证邮箱第一步：发送验证邮件
     */
    @RequestMapping(value = "/email/send")
    @Feature(FeatureType.USER_MINE)
    public WebResult emailSend(
            HttpServletRequest request,
            HttpServletResponse response) {

        User user = checkUser();

        String email = user.getEmail();
        if (!ValidationUtil.isEmail(email)) {
            throw new UtilException("您的邮箱格式不正确，请在个人页面完善好后再进行验证。");
        }

        Optional<User> optionalDbUser = userDao.findById(user.getUuid());
        //User dbUser = userDao.findOne(user.getUuid());
        if (!optionalDbUser.isPresent()) {
            throw new UtilException("不存在");
        }
        User dbUser = optionalDbUser.get();
        if (dbUser.getEmailValidate()) {
            throw new UtilException("您的邮箱已经验证通过，请勿重复验证！");
        }


        String code = DigestUtils.md5Hex(user.getEmail() + System.currentTimeMillis());

        Preference preference = preferenceService.fetch();
        String url = NetworkUtil.getHost(request) + "/api/user/email/validate?code=" + code;
        String appName = preference.getName();
        String html = "<p>您好：</p><p>您注册了" + appName + "，为确保您的帐号安全，请点击以下链接验证邮箱：</p><p><a href=\"" + url + "\">绑定邮箱</a></p><p>如果以上链接无法点击，请将地址<a href=\"" + url + "\">" + url + "</a>手动复制到浏览器地址栏中访问。</p><p>请在 24 小时内完成验证，此链接将在您使用过一次后失效。</p><p>如果您没有注册，请忽略此邮件。</p><p><br></p><p>" + appName + "团队</p><p><br></p>";

        NotificationResult result = mailService.htmlSend(user.getEmail(), "【" + appName + "】您正在验证邮箱", html);

        if (result.getStatus() == NotificationResult.Status.OK) {

            SupportValidation supportValidation = new SupportValidation();
            supportValidation.setUserUuid(user.getUuid());
            supportValidation.setEmail(email);
            supportValidation.setCode(code);
            supportValidation.setType(SupportValidation.Type.VALIDATION);

            supportValidationDao.save(supportValidation);

            return success();
        } else {
            throw new UtilException(result.getMessage());
        }

    }

    /**
     * 验证邮箱第二步：验证邮箱
     */
    @RequestMapping(value = "/email/validate")
    @Feature(FeatureType.PUBLIC)
    public WebResult emailValidate(@RequestParam String code,
                                   HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {

        SupportValidation supportValidation = supportValidationDao.findByCodeAndType(code, SupportValidation.Type.VALIDATION);

        if (supportValidation == null) {
            throw new UtilException("您的邮箱未能验证通过！");
        } else if (System.currentTimeMillis() - supportValidation.getCreateTime().getTime() > SupportValidation.EXPIRE_INTERVAL) {
            throw new UtilException("验证链接已过期，请重新验证！");
        } else {

            String userUuid = supportValidation.getUserUuid();
            Optional<User> optionalUser = userDao.findById(userUuid);
            //User user = userDao.findOne(userUuid);
            if (!optionalUser.isPresent()) {
                throw new UtilException("不存在");
            }
            User user = optionalUser.get();
            user.setEmailValidate(true);

            userDao.save(user);

            //让链接过期
            supportValidationDao.delete(supportValidation);

            String host = request.getHeader("Host");
            String url = "http://" + host + "/by/user/detail/" + userUuid;
            response.sendRedirect(url);
            return success("验证成功，请登录网站后台查看身份详情！");

        }

    }


}
