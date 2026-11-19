package com.shixun.mail.controller;

import com.shixun.common.api.ApiResponse;
import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.AuthUserContext;
import com.shixun.mail.entity.MailStar;
import com.shixun.mail.entity.UserContactList;
import com.shixun.mail.mapper.MailMapper;
import com.shixun.mail.mapper.MailStarMapper;
import com.shixun.mail.mapper.UserContactListMapper;
import com.shixun.mail.vo.MailListVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
public class ContactAndStarController {

    private final MailStarMapper mailStarMapper;
    private final MailMapper mailMapper;
    private final UserContactListMapper userContactListMapper;

    public ContactAndStarController(MailStarMapper mailStarMapper,
                                     UserContactListMapper userContactListMapper,
                                     MailMapper mailMapper) {
        this.mailStarMapper = mailStarMapper;
        this.userContactListMapper = userContactListMapper;
        this.mailMapper = mailMapper;
    }

    // ===================== 星标管理 =====================

    /**
     * 添加星标。
     */
    @PostMapping("/star/{mailId}")
    public ApiResponse<Map<String, Object>> addStar(@PathVariable Long mailId) {
        Long userId = AuthUserContext.getUserId();
        requireStarAccess(userId, mailId);
        if (mailStarMapper.existsByUserAndMail(userId, mailId)) {
            return ApiResponse.success("该邮件已星标", Map.of("mailId", mailId, "starred", true));
        }
        MailStar star = MailStar.builder()
                .userId(userId)
                .mailId(mailId)
                .createdAt(LocalDateTime.now())
                .build();
        mailStarMapper.insert(star);
        return ApiResponse.success("已标记为星标邮件", Map.of("mailId", mailId, "starred", true));
    }

    /**
     * 取消星标。
     */
    @DeleteMapping("/star/{mailId}")
    public ApiResponse<Map<String, Object>> removeStar(@PathVariable Long mailId) {
        Long userId = AuthUserContext.getUserId();
        requireStarAccess(userId, mailId);
        LambdaQueryWrapper<MailStar> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MailStar::getUserId, userId)
               .eq(MailStar::getMailId, mailId);
        mailStarMapper.delete(wrapper);
        return ApiResponse.success("已取消星标", Map.of("mailId", mailId, "starred", false));
    }

    /**
     * 获取星标邮件列表。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.shixun.mail.service.MailService mailService;
    @GetMapping("/starred")
    public ApiResponse<?> getStarredMails(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(mailService.page("starred",cursor,size));
    }

    /**
     * 检查邮件是否已被星标。
     */
    @GetMapping("/star/{mailId}/check")
    public ApiResponse<Map<String, Object>> checkStar(@PathVariable Long mailId) {
        Long userId = AuthUserContext.getUserId();
        requireStarAccess(userId, mailId);
        boolean starred = mailStarMapper.existsByUserAndMail(userId, mailId);
        return ApiResponse.success(Map.of("mailId", mailId, "starred", starred));
    }

    private void requireStarAccess(Long userId, Long mailId) {
        if (mailId == null || mailId <= 0) {
            throw BusinessException.of(400, "邮件ID不合法");
        }
        if (!mailMapper.existsMailAccess(mailId, userId)) {
            throw BusinessException.of(403, "无权操作该邮件");
        }
    }

    // ===================== 联系人名单管理 =====================

    /**
     * 添加联系人到名单（白名单/黑名单/VIP）。
     * 请求体: { "contactEmail": "xxx@example.com", "listType": "WHITELIST" | "BLACKLIST" | "VIP" }
     */
    @PostMapping("/contact-list")
    public ApiResponse<Map<String, Object>> addContact(@RequestBody Map<String, String> body) {
        Long userId = AuthUserContext.getUserId();
        String email = body.get("contactEmail");
        String listType = body.get("listType");

        if (email == null || email.isBlank()) {
            return ApiResponse.failure(400, "联系人邮箱不能为空");
        }
        if (!List.of("WHITELIST", "BLACKLIST", "VIP").contains(listType)) {
            return ApiResponse.failure(400, "名单类型必须为 WHITELIST、BLACKLIST 或 VIP");
        }

        // 白名单/VIP 互斥检查
        if (!"BLACKLIST".equals(listType) && userContactListMapper.existsByEmail(userId, email.toLowerCase())) {
            return ApiResponse.failure(400, "该联系人已在其他名单中，请先从原名单移除");
        }

        // 黑名单最高优先级: 如果加入黑名单，先从其他名单中移除
        if ("BLACKLIST".equals(listType)) {
            LambdaQueryWrapper<UserContactList> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserContactList::getUserId, userId)
                   .eq(UserContactList::getContactEmail, email.toLowerCase());
            userContactListMapper.delete(wrapper);
        }

        UserContactList contact = UserContactList.builder()
                .userId(userId)
                .contactEmail(email.toLowerCase().trim())
                .listType(listType)
                .createdAt(LocalDateTime.now())
                .build();
        userContactListMapper.insert(contact);
        return ApiResponse.success("已添加",
                Map.of("email", email, "listType", listType));
    }

    /**
     * 从名单中移除联系人。
     */
    @DeleteMapping("/contact-list/{id}")
    public ApiResponse<Map<String, Object>> removeContact(@PathVariable Long id) {
        UserContactList contact = userContactListMapper.selectById(id);
        if (contact == null || !AuthUserContext.getUserId().equals(contact.getUserId())) {
            throw BusinessException.of(403, "无权操作该联系人");
        }
        userContactListMapper.deleteById(id);
        return ApiResponse.success("已移除", Map.of("id", id));
    }

    /**
     * 获取用户的所有联系人名单。
     */
    @GetMapping("/contact-list")
    public ApiResponse<List<UserContactList>> getContactList() {
        Long userId = AuthUserContext.getUserId();
        LambdaQueryWrapper<UserContactList> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserContactList::getUserId, userId)
               .orderByDesc(UserContactList::getCreatedAt);
        return ApiResponse.success(userContactListMapper.selectList(wrapper));
    }
}
