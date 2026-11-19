package com.shixun.mail.service;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import com.shixun.mail.mapper.MailMapper;
import com.shixun.mail.vo.MailListVO;
import com.shixun.user.mapper.UserMapper;
import com.shixun.user.entity.User;
import com.shixun.common.security.AuthUserContext;
import java.time.LocalDateTime;
import java.util.*;
class MailPaginationTest {
 MailMapper mapper;MailService service;
 @BeforeEach void init(){mapper=mock(MailMapper.class);var users=mock(UserMapper.class);when(users.findById(1L)).thenReturn(new User());AuthUserContext.set("userId",1L);service=new MailService(mapper,users,null,null,null,null,null,null);}
 @AfterEach void clear(){AuthUserContext.clear();}
 MailListVO row(long id){var r=new MailListVO();r.setMailId(id);r.setSendTime(LocalDateTime.of(2026,11,17,0,0));return r;}
 @Test void stableCursorAndBoundedRecipientBatch(){
  when(mapper.selectPage(eq(1L),eq("sent"),isNull(),isNull(),eq(3))).thenReturn(List.of(row(3),row(2),row(1)));
  when(mapper.selectPageRecipients(List.of(3L,2L))).thenReturn(List.of(Map.of("mail_id",2L,"username","Receiver","email","r@example.test")));
  var page=service.page("sent",null,2);assertTrue(page.hasMore());assertEquals(2,page.items().size());assertEquals(List.of("r@example.test"),page.items().get(1).getReceiverEmails());
  when(mapper.selectPage(eq(1L),eq("sent"),any(),eq(2L),eq(3))).thenReturn(List.of(row(1)));
  when(mapper.selectPageRecipients(List.of(1L))).thenReturn(List.of());
  var next=service.page("sent",page.nextCursor(),2);assertFalse(next.hasMore());assertNull(next.nextCursor());assertEquals(1L,next.items().get(0).getMailId());
  verify(mapper,never()).selectReceiverNamesByMailId(any());verify(mapper,never()).selectReceiverEmailsByMailId(any());
 }
 @Test void rejectsMalformedCursor(){assertThrows(com.shixun.common.exception.BusinessException.class,()->service.page("inbox","broken",20));verifyNoInteractions(mapper);}
 @Test void clampsRequestedPageSize(){when(mapper.selectPage(eq(1L),eq("inbox"),isNull(),isNull(),eq(101))).thenReturn(List.of());assertFalse(service.page("inbox",null,99999).hasMore());verify(mapper,never()).selectPageRecipients(any());}
}
