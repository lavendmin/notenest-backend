package com.notenest.service;

import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.dto.EmailDTO;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;


@Service
public class EmailService {
    private final JavaMailSender emailSender;
    private final SpringTemplateEngine templateEngine;

    public EmailService(JavaMailSender emailSender, SpringTemplateEngine templateEngine) {
        this.emailSender = emailSender;
        this.templateEngine = templateEngine;
    }

    // 공통 부분 이메일
    @Async
    public void sendEmail(EmailDTO emailDTO, String status) {
        MimeMessage message = emailSender.createMimeMessage();

        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setTo(emailDTO.getTo());
            helper.setSubject("NOTENEST: " + emailDTO.getMainMessage());

            Context context = new Context();
            context.setVariable("mainMessage", emailDTO.getMainMessage());
            context.setVariable("subMessage", emailDTO.getSubMessage());

            String htmlContent = templateEngine.process("email", context);
            helper.setText(htmlContent, true);

            helper.addInline("logo", new ClassPathResource("static/images/logo.png"));
            if (status.equals("success")) {
                helper.addInline("image", new ClassPathResource("static/images/check.png"));
            } else if (status.equals("failure")){
                helper.addInline("image", new ClassPathResource("static/images/xCircle.png"));
            } else if (status.equals("verification")) {
                helper.addInline("image", new ClassPathResource("static/images/verification.png"));
            }


            emailSender.send(message);
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    // 입찰한 사람 O, 결제 전 - 구매자 (입찰자)
    public void sendBidSuccessToBidder(User bidder, Music music) {
        String subtitle = (music.getSubtitle() != null && !music.getSubtitle().isEmpty()) ? " (" + music.getSubtitle() + ")" : "";

        EmailDTO bidderNotification = new EmailDTO(
                bidder.getEmail(),
                "The successful bid has been made.",
                bidder.getNickname() + "님이 입찰한 곡 <" + music.getTitle() + subtitle + ">이(가) 낙찰되었습니다. " +
                        "낙찰을 확정 짓고 싶다면, [마이페이지-낙찰 내역-결제 대기]에서 결제 바랍니다. 결제 기한은 3일입니다. 기한 내에 결제하지 않으면 다음 사람에게 넘어가거나 경매가 무산됩니다. " +
                        "결제 완료 시, 음악 다운로드 횟수는 5회로 제한되어있습니다. 이 점 유의해주시길 바랍니다."
        );

        sendEmail(bidderNotification, "success");
    }

    // 입찰한 사람 O, 결제 후 - 작곡가
    public void sendBidSuccessToComposer(User composer, Music music) {
        String subtitle = (music.getSubtitle() != null && !music.getSubtitle().isEmpty()) ? " (" + music.getSubtitle() + ")" : "";

        EmailDTO composerNotification = new EmailDTO(
                composer.getEmail(),
                " The successful bid has been made.",
                composer.getNickname() + " 님이 등록한 곡 <" + music.getTitle() + subtitle + ">이(가) 낙찰되었습니다. " +
                        "결제 금액 입금은 며칠 소요될 수 있습니다."
        );
        sendEmail(composerNotification, "success");
    }

    // 입찰한 사람 X or 결제 기한 초과로 결제 X -> 최종 낙찰 X [경매 무산] - 작곡가
    public void sendAuctionFailureToComposer(User composer, Music music) {
        String subtitle = (music.getSubtitle() != null && !music.getSubtitle().isEmpty()) ? " (" + music.getSubtitle() + ")" : "";

        EmailDTO composerNotification = new EmailDTO(
                composer.getEmail(),
                "The auction is miscarried.",
                composer.getNickname() + " 님이 등록한 곡 <" + music.getTitle() + subtitle + ">이(가) 유찰되었습니다. " +
                        "경매 재등록을 원하시는 경우, [upload song]에서 곡을 새롭게 다시 등록해주세요."
        );
        sendEmail(composerNotification, "failure");
    }

    // 회원가입 인증코드 발송 이메일
    public void sendVerificationEmail(String to, String code) {
        EmailDTO verification = new EmailDTO(
                to,
                "Email Verification Code",
                code
        );
        sendEmail(verification, "verification");
    }
}
