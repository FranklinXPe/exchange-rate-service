package ni.org.jug.exchangerate.logic;

import ni.org.jug.exchangerate.exception.InvalidDataException;
import ni.org.jug.exchangerate.exception.UserSubscriptionNotFoundException;
import ni.org.jug.exchangerate.model.Bank;
import ni.org.jug.exchangerate.model.CentralBankExchangeRate;
import ni.org.jug.exchangerate.model.CommercialBankExchangeRate;
import ni.org.jug.exchangerate.model.EmailTask;
import ni.org.jug.exchangerate.model.UserSubscription;
import ni.org.jug.exchangerate.repository.BankRepository;
import ni.org.jug.exchangerate.repository.CentralBankExchangeRateRepository;
import ni.org.jug.exchangerate.repository.CommercialBankExchangeRateRepository;
import ni.org.jug.exchangerate.repository.EmailTaskRepository;
import ni.org.jug.exchangerate.repository.UserSubscriptionRepository;
import ni.org.jug.exchangerate.util.HtmlGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class EmailSubscriptionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailSubscriptionService.class);

    private static final HtmlGenerator EMAIL_TEMPLATE_FOR_ACTIVATION = new HtmlGenerator();
    static {
        EMAIL_TEMPLATE_FOR_ACTIVATION.html5()
                .head()
                    .style()
                        .block("body {")
                        .block("    font-family: Arial, Helvetica, sans-serif;")
                        .block("    font-size: 18px;")
                        .block("}")
                        .ln()
                        .block(".panel {")
                        .block("    margin-top: 15px;")
                        .block("    margin-bottom: 15px;")
                        .block("    padding: 5px 20px;")
                        .block("}")
                        .ln()
                        .block(".note {")
                        .block("    background-color: #ddd;")
                        .block("    border-left: 5px solid #4caf50;")
                        .block("}")
                    .closeStyle()
                .closeHead()
                .body()
                    .p()
                        .inline("Hola ").strong("%s").inline(", para poder recibir los datos de la compra/venta de d&#243;lares ")
                        .inline("en los bancos comerciales, debe presionar el siguiente enlace: ")
                        .a("%s", "Presione aqu&#237;").inline(".")
                    .closeP()
                    .div("panel", "note")
                        .p()
                            .strong("Nota:").inline(" Una vez dado de alta en nuestro sistema, recibir&#225; un correo diario con ")
                            .inline("los datos de la compra/venta y un enlace al pie del correo con el cual podr&#225; darse de baja de ")
                            .inline("nuestro sistema. Si por alguna raz&#243;n los datos de la compra/venta cambian, se volver&#225; a ")
                            .inline("enviar un correo con los nuevos datos.")
                        .closeP()
                    .closeDiv()
                .closeBody()
                .closeHtml();
    }

    private static final HtmlGenerator EMAIL_TEMPLATE_FOR_EXCHANGE_RATE_DATA = new HtmlGenerator();
    private static final int INDENTATION_LEVEL_AT_INSERTION_POINT;
    static {
        EMAIL_TEMPLATE_FOR_EXCHANGE_RATE_DATA.html5()
                .head()
                    .style()
                        .block("body {")
                        .block("    font-family: Arial, Helvetica, sans-serif;")
                        .block("    padding: 10px;")
                        .block("}")
                        .ln()
                        .block("table {")
                        .block("    border-collapse: collapse;")
                        .block("}")
                        .ln()
                        .block("td, th {")
                        .block("    border: 1px solid #ddd;")
                        .block("    padding: 6px;")
                        .block("}")
                        .ln()
                        .block("th {")
                        .block("    padding-top: 10px;")
                        .block("    padding-bottom: 10px;")
                        .block("    text-align: left;")
                        .block("    background-color: #4caf50;")
                        .block("    color: white;")
                        .block("}")
                        .ln()
                        .block("tfoot td {")
                        .block("    padding-top: 10px;")
                        .block("    padding-bottom: 10px;")
                        .block("}")
                        .ln()
                        .block("tr:nth-child(even) {")
                        .block("    background-color: #f2f2f2;")
                        .block("}")
                        .ln()
                        .block("tr:hover {")
                        .block("    background-color: #ddd;")
                        .block("}")
                        .ln()
                        .block("p {")
                        .block("    margin-top: 20px;")
                        .block("}")
                    .closeStyle()
                .closeHead()
                .body()
                    .p()
                        .inline("Los siguientes datos corresponden a la fecha: ").strong("%s").inline(".")
                    .closeP()
                    .addPlaceholder("%s");

        INDENTATION_LEVEL_AT_INSERTION_POINT = EMAIL_TEMPLATE_FOR_EXCHANGE_RATE_DATA.getIndentationLevel();

        EMAIL_TEMPLATE_FOR_EXCHANGE_RATE_DATA
                    .p()
                        .inline("Si ya no desea seguir recibiendo este correo, recuerde que puede darse de ").strong("baja")
                        .inline(" en cualquier momento con el siguiente enlace: ").a("%s", "Presione aqu&#237;").inline(".")
                    .closeP()
                .closeBody()
                .closeHtml();
    }

    private static final String HORIZONTAL_RULE =
            "----------------------------------------------------------------------------------------------------";
    private static final String BASE_URL_TO_IMAGES = "https://javanicaragua.org/wp-content/uploads/2019/10/";
    private static final DateTimeFormatter NIO_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    @Autowired
    CommercialBankExchangeRateRepository commercialBankExchangeRateRepository;

    @Autowired
    CentralBankExchangeRateRepository centralBankExchangeRateRepository;

    @Autowired
    UserSubscriptionRepository userSubscriptionRepository;

    @Autowired
    EmailTaskRepository emailTaskRepository;

    @Autowired
    BankRepository bankRepository;

    @Autowired
    JavaMailSender mailSender;

    private AtomicInteger counter = new AtomicInteger();
    private Map<String, String> images = new HashMap<>();

    @PostConstruct
    public void init() {
        for (Bank bank : bankRepository.findAll()) {
            String key = bank.getDescription().getShortDescription();
            String path = BASE_URL_TO_IMAGES + key + ".png";
            images.put(key, path);
        }
        images.put("up", BASE_URL_TO_IMAGES + "up.png");
        images.put("equal", BASE_URL_TO_IMAGES + "equal.png");
        images.put("down", BASE_URL_TO_IMAGES + "down.png");
    }

    private String generateToken() {
        long milliOfDay = LocalDateTime.now().getLong(ChronoField.MILLI_OF_DAY);
        StringBuilder token = new StringBuilder(100);
        token.append(counter.incrementAndGet()).append('-').append(milliOfDay);
        return token.toString();
    }

    private void sendEmailToEnableSubscription(String email, String fullName, String url) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper messageHelper = new MimeMessageHelper(message);

        messageHelper.setTo(email);
        messageHelper.setSubject("Servicio de Compra/Venta de dolares en los Bancos Comerciales");
        messageHelper.setText(EMAIL_TEMPLATE_FOR_ACTIVATION.asHtml(fullName, url), true);

        mailSender.send(message);
    }

    public void createSubscription(String fullName, String email, UriComponentsBuilder uriComponentsBuilder) throws MessagingException {
        UserSubscription userSubscription = userSubscriptionRepository.findByEmail(email);
        String token = generateToken();

        if (userSubscription == null) {
            LOGGER.debug("Crear nueva suscripcion con email [{}] y nombre completo [{}]", email, fullName);

            userSubscription = new UserSubscription();
            userSubscription.setFullName(fullName);
            userSubscription.setEmail(email);
            userSubscription.setToken(token);
            userSubscription.setActive(false);
            userSubscriptionRepository.save(userSubscription);
        } else {
            LOGGER.debug("Actualizar suscripcion con email [{}] y nombre completo [{}]", email, fullName);

            userSubscription.setFullName(fullName);
            userSubscription.setToken(token);
            userSubscription.setActive(false);
        }

        String urlToActivate = uriComponentsBuilder.path("/activate/{email}/{token}").buildAndExpand(email, token).encode().toUriString();

        sendEmailToEnableSubscription(email, fullName, urlToActivate);
    }

    public void activateSubscription(String email, String token) {
        UserSubscription userSubscription = userSubscriptionRepository.findByEmail(email);
        validateUserSubscription(userSubscription, email, token);
        userSubscription.setActive(Boolean.TRUE);
    }

    public void deactivateSubscription(String email, String token) {
        UserSubscription userSubscription = userSubscriptionRepository.findByEmail(email);
        validateUserSubscription(userSubscription, email, token);
        userSubscription.setActive(Boolean.FALSE);
    }

    private void validateUserSubscription(UserSubscription userSubscription, String email, String token) {
        if (userSubscription == null) {
            throw new UserSubscriptionNotFoundException(email);
        }
        if (!userSubscription.getToken().equals(token)) {
            throw new InvalidDataException("El token [" + token + "] es incorrecto");
        }
    }

    private BigDecimal getCurrentOfficialExchangeRate() {
        Integer officialExchangeRateId = CentralBankExchangeRate.calculateId(LocalDate.now());
        Optional<CentralBankExchangeRate> officialExchangeRateData = centralBankExchangeRateRepository.findById(officialExchangeRateId);
        BigDecimal officialExchangeRate = officialExchangeRateData.map(CentralBankExchangeRate::getAmount).orElse(BigDecimal.ZERO);
        return officialExchangeRate;
    }

    private String constructHtmlTableWithExchangeRateData() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.plusDays(-1);
        Map<String, Map<LocalDate, CommercialBankExchangeRate>> exchangeRateByBankAndDate;

        exchangeRateByBankAndDate = commercialBankExchangeRateRepository.findByDateBetween(yesterday, today)
                .stream()
                .collect(Collectors.groupingBy(exchangeRate -> exchangeRate.getBank().getDescription().getShortDescription(),
                        HashMap::new, Collectors.toMap(CommercialBankExchangeRate::getDate, Function.identity())));

        boolean todayDataIsPresent = exchangeRateByBankAndDate.entrySet()
                .stream()
                .map(Map.Entry::getValue)
                .map(map -> map.entrySet())
                .flatMap(Set::stream)
                .map(Map.Entry::getKey)
                .filter(date -> today.equals(date))
                .findAny().isPresent();

        if (!todayDataIsPresent) {
            LOGGER.info("No hay datos de compra/venta para la fecha actual");
            return "";
        }

        HtmlGenerator html = new HtmlGenerator(INDENTATION_LEVEL_AT_INSERTION_POINT);
        html.table()
                .tr()
                .th("Banco")
                .th("Venta")
                .th("Compra")
                .th("Tipo de Cambio Oficial")
                .closeTr();

        BigDecimal currentOfficialExchangeRate = getCurrentOfficialExchangeRate();

        for (Map.Entry<String, Map<LocalDate, CommercialBankExchangeRate>> bankEntry : exchangeRateByBankAndDate.entrySet()) {
            CommercialBankExchangeRate exchangeRateToday = bankEntry.getValue().get(today);
            CommercialBankExchangeRate exchangeRateYesterday = bankEntry.getValue().get(yesterday);

            if (exchangeRateToday == null) {
                LOGGER.info("El dia de hoy no se encontraron datos de compra/venta para el banco {}", bankEntry.getKey());
                continue;
            }

            String tendency = "";

            if (exchangeRateYesterday != null) {
                switch (exchangeRateToday.getSell().compareTo(exchangeRateYesterday.getSell())) {
                    case -1:
                        tendency = "down";
                        break;
                    case 0:
                        tendency = "equal";
                        break;
                    case 1:
                        tendency = "up";
                        break;
                    default:
                        tendency = "";
                }
            }

            html.tr()
                    .td()
                        .img(images.get(bankEntry.getKey()), bankEntry.getKey(), 40, 40)
                    .closeTd()
                    .td()
                        .strong(exchangeRateToday.getSell(), exchangeRateToday.getBestSellPrice()).nbsp().nbsp().nbsp()
                        .img(images.get(tendency), tendency, 15, 15)
                    .closeTd()
                    .td()
                        .strong(exchangeRateToday.getBuy(), exchangeRateToday.getBestBuyPrice())
                    .closeTd()
                    .td(currentOfficialExchangeRate)
                    .closeTr();
        }

        html.tfoot()
                .tr()
                    .td(4)
                        .strong("Nota:").inline(" Las mejores opciones de compra/venta est&#225;n marcadas con ").strong("negrita")
                    .closeTd()
                .closeTr()
                .closeTfoot()
                .closeTable();

        return html.asHtml();
    }

    @Async
    public void sendEmailWithExchangeRateData(String httpUrl) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        List<UserSubscription> subscriptions = userSubscriptionRepository.findActiveSubsciptionsWithNoEmailSent(start, end);

        if (!subscriptions.isEmpty()) {
            String htmlTableWithData = constructHtmlTableWithExchangeRateData();

            if (htmlTableWithData.isEmpty()) {
                return;
            }

            String today = NIO_DATE_FORMATTER.format(LocalDate.now());

            for (UserSubscription subscription : subscriptions) {
                LOGGER.info("Enviando correo electronico a [{}]", subscription.getEmail());

                String url = UriComponentsBuilder.fromHttpUrl(httpUrl).path("/deactivate/{email}/{token}").buildAndExpand(
                        subscription.getEmail(), subscription.getToken()).encode().toUriString();
                String emailContent = EMAIL_TEMPLATE_FOR_EXCHANGE_RATE_DATA.asHtml(today, htmlTableWithData, url);

                LOGGER.debug(HORIZONTAL_RULE);
                LOGGER.debug("Email:");
                LOGGER.debug(emailContent);
                LOGGER.debug(HORIZONTAL_RULE);

                MimeMessage message = mailSender.createMimeMessage();

                try {
                    MimeMessageHelper messageHelper = new MimeMessageHelper(message);
                    messageHelper.setTo(subscription.getEmail());
                    messageHelper.setSubject("Datos de la compra/venta de dolares en los Bancos Comerciales");
                    messageHelper.setText(emailContent, true);

                    mailSender.send(message);

                    EmailTask emailTask = new EmailTask();
                    emailTask.setUserSubscription(subscription);
                    emailTask.setDate(LocalDateTime.now());
                    emailTaskRepository.save(emailTask);
                } catch (MessagingException | MailException me) {
                    String error = String.format("Ha ocurrido un error durante el envio del correo electronico a la direccion [%s]",
                            subscription.getEmail());
                    LOGGER.error(error, me);
                }
            }
        } else {
            LOGGER.info("No se encontraron usuarios activos pendientes de enviar correo");
        }
    }

}
