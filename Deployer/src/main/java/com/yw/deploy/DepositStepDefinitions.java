package com.yw.deploy;

import cucumber.api.java.en.When;
import cucumber.api.PendingException;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;

public class DepositStepDefinitions{

    @Given("^a User has no money in their account$")
    public void a_User_has_no_money_in_their_current_account() {
        User user = new User();
        Account account = new Account();
        user.setAccount(account);
    }

    @When("^£(\\d+) is deposited in to the account$")
    public void £_is_deposited_in_to_the_account(int arg1) {
        // Express the Regexp above with the code you wish you had
        throw new PendingException();
    }

    @Then("^the balance should be £(\\d+)$")
    public void the_balance_should_be_£(int arg1) {
        // Express the Regexp above with the code you wish you had
        throw new PendingException();
    }

    private class User {
        private Account account;

        public void setAccount(Account account) {
            this.account = account;
        }
    }

    private class Account {
    }
}