require File.dirname(File.join(__rhoGetCurrentDir(), __FILE__)) + '/../../../../spec_helper'
require 'net/http'
require File.dirname(File.join(__rhoGetCurrentDir(), __FILE__)) + "/fixtures/classes"

describe "Net::HTTPHeader#basic_auth when passed account, password" do
  before(:each) do
    @headers = NetHTTPHeaderSpecs::Example.new
  end

  it "sets the 'Authorization' Header entry for basic authorization" do
    @headers.basic_auth("rubyspec", "rocks")
    @headers["Authorization"].should == "Basic cnVieXNwZWM6cm9ja3M="
  end
end