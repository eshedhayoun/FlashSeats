package com.flashseats.flashseats.payment.dto;
/*explantion on how records work : https://www.baeldung.com/java-record-keyword 
but the tldr it just a imutable data structre that akready generates all the func needed

this is a basic data structre to hold the information required for payment request
and more likely than not change on further implemention but for the mock payment it should be
sufficent*/
public record PaymentResult(
    boolean successStatus,
    String transactionReference
) {}
